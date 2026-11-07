package com.cbiv.sharedworlds.fs;

import com.cbiv.sharedworlds.common.SharedWorldsFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/*
Extension of WorldSource specifically for worlds that lie on a directory path (network drive, syncthing, etc.)
 */
public class DirectoryWorldSource implements WorldSource{

    private static final Logger LOGGER = LoggerFactory.getLogger("DirectoryWorldSource");
    // Suffix for files being written; renamed into place once complete
    private static final String PARTIAL_SUFFIX = ".sharedworlds-partial";

    private final Path root;

    public DirectoryWorldSource(Path root) {
        this.root = root;
    }

    @Override
    public Optional<InputStream> openMetadata() throws IOException {
        Path meta = root.resolve(SharedWorldsFiles.WORLD_METADATA);
        return Files.exists(meta)
                ? Optional.of(Files.newInputStream(meta))
                : Optional.empty();
    }

    @Override
    public InputStream openLevelDat() throws IOException {
        return Files.newInputStream(root.resolve("level.dat"));
    }

    @Override
    public Optional<InputStream> openLock() throws IOException {
        requireReachable();
        try {
            return Optional.of(Files.newInputStream(lockFile()));
        } catch (NoSuchFileException e) {
            return Optional.empty();
        }
    }

    @Override
    public boolean tryCreateLock(byte[] contents) throws IOException {
        requireReachable();
        // CREATE_NEW fails if the file exists, as one filesystem operation, so two clients on the same
        // filesystem can't both create it. Across a sync service (Syncthing, Drive for desktop) another
        // computer only sees the file after it syncs; see "Locks over sync services" in the decision log.
        try (OutputStream out = Files.newOutputStream(lockFile(),
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            out.write(contents);
            return true;
        } catch (FileAlreadyExistsException e) {
            return false;
        }
    }

    @Override
    public void deleteLock() throws IOException {
        requireReachable();
        Files.deleteIfExists(lockFile());
    }

    @Override
    public void pushFiles(Path localWorldDir, List<String> entries) throws IOException {
        requireReachable();
        if (!Files.isRegularFile(localWorldDir.resolve("level.dat"))) {
            // Mirroring an incomplete world would delete the source's copy of what's missing
            throw new IOException("Refusing to push " + localWorldDir + ": it has no level.dat");
        }
        // TODO send only what changed (decisions 9 and 10); for now every file is copied whole
        int written = 0;
        int removed = 0;
        for (String entry : entries) {
            Path from = localWorldDir.resolve(entry);
            Path to = root.resolve(entry);
            Set<Path> pushed = new HashSet<>();
            if (Files.exists(from, LinkOption.NOFOLLOW_LINKS)) {
                written += pushTree(from, to, pushed);
            }
            // Within a synced entry the source mirrors the local copy, so a file removed locally doesn't come back
            // on the next pull. Nothing outside the synced entries is ever touched (decision 6).
            removed += removeNotPushed(to, pushed);
        }
        // Partial files left at the top level by an interrupted push (those inside entries were removed above)
        try (var top = Files.newDirectoryStream(root, "*" + PARTIAL_SUFFIX)) {
            for (Path partial : top) {
                Files.deleteIfExists(partial);
            }
        }
        LOGGER.info("Pushed {} files to {} ({} removed)", written, describe(), removed);
    }

    @Override
    public void writeMetadata(byte[] contents) throws IOException {
        requireReachable();
        Path meta = root.resolve(SharedWorldsFiles.WORLD_METADATA);
        Path partial = meta.resolveSibling(meta.getFileName() + PARTIAL_SUFFIX);
        Files.write(partial, contents);
        replace(partial, meta);
    }

    @Override
    public WorldMaterialization materialize() throws IOException {
        requireReachable();
        // A directory source is already a complete world folder, so nothing needs to be built.
        // The returned directory IS the shared copy: callers must only copy from it, never
        // move, modify or delete it.
        return new WorldMaterialization() {
            @Override
            public Path worldDirectory() {
                return root;
            }

            @Override
            public boolean isReusable() {
                return true; // the directory persists, so it can be materialized from again
            }

            @Override
            public void close() {
                // Nothing to clean up: no temp files were created.
            }
        };
    }

    @Override
    public String describe() {
        return "directory:" + root;
    }

    /* ------------------------------------------------------------
       Internals
       ------------------------------------------------------------ */

    private Path lockFile() {
        return root.resolve(SharedWorldsFiles.WORLD_LOCK);
    }

    /** A missing folder (e.g. the drive is offline) must be an error, never read as "no lock" or "empty". */
    private void requireReachable() throws IOException {
        if (!Files.isDirectory(root)) {
            throw new IOException("Shared world folder is not reachable: " + root);
        }
    }

    /**
     * Copies a local file or folder into the source. Each file is written to a partial
     * file first and renamed into place, so a reader never sees a half-written file.
     *
     * @param pushed collects every source path written
     * @return the number of files written
     */
    private int pushTree(Path from, Path to, Set<Path> pushed) throws IOException {
        int[] count = {0};
        Files.walkFileTree(from, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path target = to.resolve(from.relativize(dir).toString());
                Files.createDirectories(target);
                pushed.add(target);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!attrs.isRegularFile()) {
                    LOGGER.warn("Not pushing {}: not a regular file", file);
                    return FileVisitResult.CONTINUE;
                }
                Path target = to.resolve(from.relativize(file).toString());
                Path partial = target.resolveSibling(target.getFileName() + PARTIAL_SUFFIX);
                Files.copy(file, partial, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                replace(partial, target);
                pushed.add(target);
                count[0]++;
                return FileVisitResult.CONTINUE;
            }
        });
        return count[0];
    }

    /** Deletes everything under {@code target} that this push didn't write. */
    private static int removeNotPushed(Path target, Set<Path> pushed) throws IOException {
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return 0;
        }
        int[] count = {0};
        Files.walkFileTree(target, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!pushed.contains(file)) {
                    Files.delete(file);
                    count[0]++;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) throw exc;
                if (!pushed.contains(dir)) {
                    Files.delete(dir);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return count[0];
    }

    private static void replace(Path partial, Path target) throws IOException {
        try {
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.deleteIfExists(partial);
            throw e;
        }
    }

}
