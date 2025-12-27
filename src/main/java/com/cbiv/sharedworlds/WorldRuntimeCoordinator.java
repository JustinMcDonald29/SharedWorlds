package com.cbiv.sharedworlds;

import java.nio.file.Path;
import com.cbiv.sharedworlds.lock.*;

public class WorldRuntimeCoordinator {

    private final ClientIdentity identity;

    private boolean serverStarted = false;
    private Path activeWorldDir;
    private LockManager lockManager;

    public WorldRuntimeCoordinator(ClientIdentity identity){
        this.identity = identity;
    }

    public void beginWorldSession(Path worldDir) {
        this.activeWorldDir = worldDir;
        this.serverStarted = false;
        this.lockManager = new LockManager(identity);
    }

    public Path getWorldDirectory() {
        return this.activeWorldDir;
    }

    public void onServerStarted() {
        this.serverStarted = true;
        lockManager.createLock(this.activeWorldDir);
    }

    public boolean isServerStarted() {
        return this.serverStarted;
    }

    public void onServerStopping() {
        if (this.serverStarted) {
            lockManager.deleteLock(this.activeWorldDir);
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


}
