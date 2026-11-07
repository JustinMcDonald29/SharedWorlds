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

    /*
     * Locks. A source only stores the lock file's bytes; LockManager decides what they mean.
     * None of these may require materializing the world.
     */

    /**
     * Returns a stream to the world's lock file if one exists.
     *
     * @throws IOException if the source can't be reached. This must never be reported as "no lock".
     */
    Optional<InputStream> openLock() throws IOException;

    /**
     * Creates the lock file with the given contents, only if none exists. Must never overwrite an existing
     * lock. Implementations should make this as close to atomic as the backend allows (a directory uses
     * CREATE_NEW; a git source could rely on a push being rejected when someone else pushed first).
     *
     * @return true if this call created the lock, false if a lock already existed
     */
    boolean tryCreateLock(byte[] contents) throws IOException;

    /**
     * Deletes the lock file. Does nothing if there isn't one. The caller (LockManager) checks ownership first.
     */
    void deleteLock() throws IOException;

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
