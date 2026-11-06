package com.cbiv.sharedworlds.fs;

import com.cbiv.sharedworlds.common.SharedWorldsFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * Replaces the local copy of a shared world in saves/ with a materialized copy of its source.
 *
 * Steps:
 *  1. Copy only the whitelisted parts of the source into a fresh staging folder.
 *  2. Check the staging folder looks like a world (level.dat + region/).
 *  3. Rename the current local copy to a timestamped backup.
 *  4. Rename the staging folder into the local copy's place.
 * If anything fails before step 4 completes, the local copy is left (or put back) where it was.
 *
 * Deliberately plain file code: no Minecraft classes, no UI, no coordinator state, so it can move to a
 * background thread later. The caller decides when injection should happen.
 *
 * Staging and backup folders live in a work directory OUTSIDE saves/ so they never appear in the world
 * list, but it must be on the same drive as saves/ for the renames to be atomic.
 */
public final class WorldInjector {

    private static final Logger LOGGER = LoggerFactory.getLogger("WorldInjector");

    /** Top-level entries copied from the source (decision log, "What to copy on pull"). */
    public static final List<String> PULL_ENTRIES = List.of(
            "level.dat",
            "region",
            "entities",
            "DIM-1",
            "DIM1",
            "dimensions",
            "data",
            "datapacks",
            "poi",
            "playerdata",
            "advancements",
            "stats",
            SharedWorldsFiles.WORLD_METADATA
    );
    // Never copied: session.lock, level.dat_old, lock.json (anything not listed above is skipped).

    private static final DateTimeFormatter BACKUP_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Path stagingRoot;
    private final Path backupRoot;

    /**
     * @param workDir folder the injector may use for staging/ and backups/. Must be on the same drive as
     *                saves/ (e.g. the game directory's sharedworlds/ folder).
     */
    public WorldInjector(Path workDir) {
        this.stagingRoot = workDir.resolve("staging");
        this.backupRoot = workDir.resolve("backups");
    }

    /**
     * Replaces localWorldDir with the contents of sourceWorldDir.
     *
     * @param sourceWorldDir a complete world folder (from WorldSource.materialize()); only read, never changed
     * @param localWorldDir  the world's folder in saves/; doesn't need to exist yet (first pull)
     * @return where the previous local copy was backed up, if there was one
     * @throws IOException if injection failed; the local copy is then unchanged
     */
    public InjectionResult inject(Path sourceWorldDir, Path localWorldDir) throws IOException {
        String worldName = localWorldDir.getFileName().toString();
        if (!Files.isDirectory(sourceWorldDir)) {
            throw new IOException("Source world folder does not exist: " + sourceWorldDir);
        }

        // 1. Stage
        Files.createDirectories(stagingRoot);
        Path staging = stagingRoot.resolve(worldName);
        if (Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) {
            LOGGER.warn("Removing leftover staging folder {}", staging);
            deleteRecursively(staging);
        }
        Files.createDirectory(staging);

        try {
            for (String entry : PULL_ENTRIES) {
                Path from = sourceWorldDir.resolve(entry);
                if (Files.exists(from, LinkOption.NOFOLLOW_LINKS)) {
                    copyRecursively(from, staging.resolve(entry));
                }
            }
            // No metadata in the source (early testing / first push not done yet): keep the local one, so the
            // world keeps its identity.
            Path localMeta = localWorldDir.resolve(SharedWorldsFiles.WORLD_METADATA);
            Path stagedMeta = staging.resolve(SharedWorldsFiles.WORLD_METADATA);
            if (!Files.exists(stagedMeta) && Files.isRegularFile(localMeta)) {
                Files.copy(localMeta, stagedMeta, StandardCopyOption.COPY_ATTRIBUTES);
            }

            // 2. Validate
            validate(staging);
        } catch (IOException | RuntimeException e) {
            tryDelete(staging);
            throw e;
        }

        // 3. Back up the current local copy
        Optional<Path> backup = Optional.empty();
        if (Files.exists(localWorldDir, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(backupRoot);
            Path backupDir = uniqueBackupPath(worldName);
            try {
                move(localWorldDir, backupDir);
            } catch (IOException e) {
                tryDelete(staging);
                throw new IOException("Could not back up " + localWorldDir + " (is a file in it open?)", e);
            }
            backup = Optional.of(backupDir);
        }

        // 4. Swap the staged copy in
        try {
            move(staging, localWorldDir);
        } catch (IOException e) {
            if (backup.isPresent()) {
                try {
                    move(backup.get(), localWorldDir);
                } catch (IOException restoreError) {
                    e.addSuppressed(restoreError);
                    LOGGER.error("Injection failed AND the local copy could not be restored. "
                            + "Your previous copy of {} is at {}", worldName, backup.get());
                }
            }
            tryDelete(staging);
            throw e;
        }

        LOGGER.info("Injected {} into {}{}", sourceWorldDir, localWorldDir,
                backup.map(b -> " (previous copy backed up to " + b + ")").orElse(""));
        return new InjectionResult(localWorldDir, backup);
    }

    /** @param backup where the previous local copy was moved, empty if there wasn't one */
    public record InjectionResult(Path worldDir, Optional<Path> backup) {}

    // TODO move into WorldBlueprint once it has real rules (decision 7)
    private static void validate(Path staging) throws IOException {
        if (!Files.isRegularFile(staging.resolve("level.dat"))) {
            throw new IOException("Source world has no level.dat");
        }
        if (!Files.isDirectory(staging.resolve("region"))) {
            throw new IOException("Source world has no region/ folder");
        }
    }

    private Path uniqueBackupPath(String worldName) {
        String base = worldName + "-" + LocalDateTime.now().format(BACKUP_STAMP);
        Path candidate = backupRoot.resolve(base);
        for (int i = 2; Files.exists(candidate, LinkOption.NOFOLLOW_LINKS); i++) {
            candidate = backupRoot.resolve(base + "-" + i);
        }
        return candidate;
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // Different drive: a non-atomic directory move would mean copying, which we don't want here.
            throw new IOException("Cannot rename " + from + " to " + to
                    + "; the SharedWorlds work folder must be on the same drive as saves/", e);
        }
    }

    /** Copies a file or folder tree. Symbolic links are skipped, not followed. */
    private static void copyRecursively(Path from, Path to) throws IOException {
        Files.walkFileTree(from, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(to.resolve(from.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (attrs.isSymbolicLink()) {
                    LOGGER.warn("Skipping symbolic link in source world: {}", file);
                } else if (attrs.isRegularFile()) {
                    Files.copy(file, to.resolve(from.relativize(file).toString()),
                            StandardCopyOption.COPY_ATTRIBUTES);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteRecursively(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) throw exc;
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void tryDelete(Path dir) {
        try {
            if (Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) deleteRecursively(dir);
        } catch (IOException e) {
            LOGGER.warn("Could not remove staging folder {}", dir, e);
        }
    }
}
