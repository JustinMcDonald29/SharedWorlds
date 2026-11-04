package com.cbiv.sharedworlds.fs;

import java.io.InputStream;
import java.io.IOException;
import java.util.Optional;

public interface WorldSource {

    /**
     * Returns a stream to sharedworld.meta.json if it exists.
     * Absence is valid and expected.
     */
    Optional<InputStream> openMetadata() throws IOException;

    /**
     * Returns a stream to level.dat (compressed NBT).
     * Must exist for a valid world.
     */
    InputStream openLevelDat() throws IOException;

    /**
     * Materializes the world into a local directory for use by Minecraft.
     * Implementations may:
     *  - copy files
     *  - extract archives
     *  - checkout git
     *  - download snapshots
     */
    WorldMaterialization materialize() throws IOException;

    /**
     * A stable identifier for logging/debugging.
     * e.g. "directory:D:/MinecraftWorlds/ConfigTesting"
     */
    String describe();
}
