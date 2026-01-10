package com.cbiv.sharedworlds;

import java.nio.file.Path;
import com.cbiv.sharedworlds.lock.*;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;

public class WorldRuntimeCoordinator {

    private static final Logger LOGGER = LoggerFactory.getLogger("WorldRuntimeCoordinator");
    private final ClientIdentity identity;

    private boolean serverStarted = false;
    private Path activeWorldDir;
    private LockManager lockManager;

    public WorldRuntimeCoordinator(ClientIdentity id){
        this.identity = id;
        this.lockManager = new LockManager(identity);
    }

    public void beginWorldSession(Path worldDir) {
        this.activeWorldDir = worldDir;
        this.serverStarted = false;
        //this.lockManager = new LockManager(identity);
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
