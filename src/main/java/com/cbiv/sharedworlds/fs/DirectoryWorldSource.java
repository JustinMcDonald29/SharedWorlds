package com.cbiv.sharedworlds.fs;

import java.io.InputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
    public WorldMaterialization materialize() {

        return () -> root;
    }
    @Override
    public String describe() {
        return "directory:" + root;
    }
}
