package com.cbiv.sharedworlds;

import java.nio.file.Path;

import com.cbiv.sharedworlds.fs.WorldSource;
import com.cbiv.sharedworlds.lock.*;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
import com.cbiv.sharedworlds.config.SharedWorldMetadataRegistry;

public class WorldRuntimeCoordinator {

    private static final Logger LOGGER = LoggerFactory.getLogger("WorldRuntimeCoordinator");
    private final ClientIdentity identity;

    private boolean serverStarted = false;
    private Path activeWorldDir;
    private LockManager lockManager;
    private SharedWorldMetadataRegistry registry;
    private WorldSource sourceWorld;

    public WorldRuntimeCoordinator(ClientIdentity id){
        this.identity = id;
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
        /*
        * if(registry.isShared(worldDir){
        *   this.sourceWorld = registry.getSource(worldDir)
        * } else {
        *   // This is not a shared world, the mod doesn't need to do anything, launch as normal
        * }
        * if(!this.lockmanager.isLocked(this.sourceWorld){
        *   if(this.registry.isSourceNewer(worldDir, this.sourceWorld){
        *       // Pass off to filesystem for world injection
        *   } else {
        *       // local world is newer, launch local
        *   }
        * } else {
        *   // world is locked, block launch
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

    public void onServerStarted() {
        this.serverStarted = true;
        if(this.activeWorldDir != null) {
            lockManager.createLock(this.activeWorldDir);
        } else {
            LOGGER.debug("activeWorldDir is null onServerStarted");
        }
    }

    public boolean isServerStarted() {
        return this.serverStarted;
    }

    public void onServerStopping() {
        if (this.serverStarted && this.activeWorldDir != null) {
            lockManager.deleteLock(this.activeWorldDir);
        } else if (this.activeWorldDir == null) {
            LOGGER.debug("activeWorldDir is null onServerStopping");
        }
        this.serverStarted = false;
    }

    public void endWorldSession() {
        this.activeWorldDir = null;
        this.lockManager = null;
    }

    public boolean isWorldActive() {
        return activeWorldDir != null;
    }

    // TODO add onWorldDeleted to handle global config implications of worlds deleted in game

    // TODO add onWorldRename to handle global config implications of worlds renamed


}
