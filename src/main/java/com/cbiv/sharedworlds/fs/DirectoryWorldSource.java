package com.cbiv.sharedworlds.fs;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import com.cbiv.sharedworlds.common.SharedWorldsFiles;

/*
Extension of WorldSource specifically for worlds that lie on a directory path (network drive, syncthing, etc.)
 */
public class DirectoryWorldSource implements WorldSource{
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
    public WorldMaterialization materialize() {
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

    private Path lockFile() {
        return root.resolve(SharedWorldsFiles.WORLD_LOCK);
    }

    /** A missing folder (e.g. the drive is offline) must be an error, never read as "no lock". */
    private void requireReachable() throws IOException {
        if (!Files.isDirectory(root)) {
            throw new IOException("Shared world folder is not reachable: " + root);
        }
    }
}
