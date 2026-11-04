package com.cbiv.sharedworlds;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import com.cbiv.sharedworlds.fs.WorldSource;
import com.cbiv.sharedworlds.lock.*;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
import com.cbiv.sharedworlds.config.GlobalSharedWorldConfig;
import com.cbiv.sharedworlds.config.SharedWorldMetadataRegistry;

public class WorldRuntimeCoordinator {

    private static final Logger LOGGER = LoggerFactory.getLogger("WorldRuntimeCoordinator");
    private final ClientIdentity identity;
    private final GlobalSharedWorldConfig config;

    private boolean serverStarted = false;
    // True once the server for the active world has started, i.e. the world was actually played this session
    private boolean serverHasRun = false;
    private Path activeWorldDir;
    private LockManager lockManager;
    private SharedWorldMetadataRegistry registry;
    private WorldSource sourceWorld;

    public WorldRuntimeCoordinator(ClientIdentity id, GlobalSharedWorldConfig config){
        this.identity = id;
        this.config = config;
        this.lockManager = new LockManager(identity);
        this.registry = new SharedWorldMetadataRegistry();
    }

    /**
     * Called by WorldOpenIntercept before Minecraft opens an existing world.
     *
     * @return true if the launch should continue, false if it should be cancelled
     *         (e.g. the world is locked by another player)
     */
    public boolean beginWorldSession(Path worldDir) {
        this.activeWorldDir = worldDir;
        this.serverStarted = false;
        this.serverHasRun = false;
        this.sourceWorld = null;
        String worldName = worldDir.getFileName().toString();

        // 1. Not in this computer's config -> not a shared world, launch as normal
        Optional<GlobalSharedWorldConfig.SharedWorldEntry> entry = config.findByLocalName(worldName);
        if (entry.isEmpty()) {
            LOGGER.debug("World {} is not shared", worldName);
            return true;
        }

        // 2. Build its source. If that fails, fail open and launch the local copy
        Optional<WorldSource> source = registry.getSource(entry.get().source());
        if (source.isEmpty()) {
            LOGGER.warn("Could not resolve the source for shared world {}, launching the local copy", worldName);
            return true;
        }
        this.sourceWorld = source.get();
        LOGGER.info("World {} is shared, source: {}", worldName, sourceWorld.describe());

        /*
        * Remaining steps:
        * if (source is locked) {
        *   // world is locked, block launch -> return false
        * }
        * if (registry.isSourceNewer(worldDir, sourceWorld)) {
        *   // Pass off to filesystem for world injection
        * } else {
        *   // local world is newer, launch local
        * }
        * */

        // Crude local test, carried over from WorldOpenIntercept: locks currently live in the local world
        // folder, so this only logs and never blocks. Once locks live in the source, a locked world
        // returns false here.
        if (lockManager.isLocked(worldDir)) {
            LOGGER.warn("LOCK FILE DETECTED for world {}", worldDir.getFileName());
        }

        return true;
    }


    public Path getWorldDirectory() {
        return this.activeWorldDir;
    }

    /**
     * Called when an integrated server has finished starting.
     *
     * @param serverWorldDir the world folder the server is actually running
     */
    public void onServerStarted(Path serverWorldDir) {
        this.serverStarted = true;
        if (this.activeWorldDir == null) {
            LOGGER.debug("No active SharedWorlds session for server world {}", serverWorldDir);
            return;
        }
        if (!isSameWorld(this.activeWorldDir, serverWorldDir)) {
            // The server is running a world that never went through beginWorldSession (e.g. a newly created
            // world), so the active session is stale. Drop it rather than lock the wrong world.
            LOGGER.warn("Server started world {} but the active session is for {}; discarding stale session",
                    serverWorldDir, this.activeWorldDir);
            resetSession();
            return;
        }
        this.serverHasRun = true;
        lockManager.createLock(this.activeWorldDir);
    }

    public boolean isServerStarted() {
        return this.serverStarted;
    }

    /**
     * Called when the integrated server starts shutting down. The world is NOT fully saved yet at this
     * point, so the lock is kept: it is released in endWorldSession, once the session has closed.
     */
    public void onServerStopping() {
        this.serverStarted = false;
    }

    /**
     * Called when the active world's LevelStorage.Session closes, after Minecraft has finished saving it
     * (or when a launch was abandoned before its server started). Resets all per-session state so the next
     * launch starts clean. The LockManager is kept: it lives for the whole client.
     */
    public void endWorldSession() {
        if (this.serverHasRun && this.activeWorldDir != null) {
            // TODO push the world back to its source here, before releasing the lock. The lock must only be
            // released once the push has completed, so no one can pull a half-written world.
            lockManager.deleteLock(this.activeWorldDir);
        }
        resetSession();
        this.serverStarted = false;
    }

    /**
     * @return true if directoryName is the folder name of the world in the active session
     */
    public boolean isActiveWorld(String directoryName) {
        return this.activeWorldDir != null
                && this.activeWorldDir.getFileName().toString().equals(directoryName);
    }

    /**
     * @return true if the active world's server has started this session (the world was actually played)
     */
    public boolean hasServerRun() {
        return this.serverHasRun;
    }

    private void resetSession() {
        this.activeWorldDir = null;
        this.sourceWorld = null;
        this.serverHasRun = false;
    }

    private static boolean isSameWorld(Path a, Path b) {
        if (a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize())) {
            return true;
        }
        try {
            return Files.isSameFile(a, b);
        } catch (IOException e) {
            return false;
        }
    }

    public boolean isWorldActive() {
        return activeWorldDir != null;
    }

    // TODO add onWorldDeleted to handle global config implications of worlds deleted in game

    // TODO add onWorldRename to handle global config implications of worlds renamed


}
