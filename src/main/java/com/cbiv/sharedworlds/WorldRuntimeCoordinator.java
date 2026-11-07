package com.cbiv.sharedworlds;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import com.cbiv.sharedworlds.common.SharedWorldsFiles;
import com.cbiv.sharedworlds.fs.WorldBlueprint;
import com.cbiv.sharedworlds.fs.WorldInjector;
import com.cbiv.sharedworlds.fs.WorldMaterialization;
import com.cbiv.sharedworlds.fs.WorldSource;
import com.cbiv.sharedworlds.lock.*;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
import com.cbiv.sharedworlds.config.GlobalSharedWorldConfig;
import com.cbiv.sharedworlds.config.MetadataValidationException;
import com.cbiv.sharedworlds.config.SharedWorldMetadata;
import com.cbiv.sharedworlds.config.SharedWorldMetadataRegistry;
import com.cbiv.sharedworlds.config.WorldDecision;

public class WorldRuntimeCoordinator {

    private static final Logger LOGGER = LoggerFactory.getLogger("WorldRuntimeCoordinator");
    private final ClientIdentity identity;
    private final GlobalSharedWorldConfig config;
    private final WorldInjector injector;

    private boolean serverStarted = false;
    // True once the server for the active world has started, i.e. the world was actually played this session
    private boolean serverHasRun = false;
    private Path activeWorldDir;
    private LockManager lockManager;
    private SharedWorldMetadataRegistry registry;
    private WorldSource sourceWorld;
    // True while this client holds the lock in sourceWorld
    private boolean holdsLock = false;

    public WorldRuntimeCoordinator(ClientIdentity id, GlobalSharedWorldConfig config, WorldInjector injector){
        this.identity = id;
        this.config = config;
        this.injector = injector;
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
        // Clear anything left by a launch that never closed a session (this also releases its lock)
        resetSession();
        this.activeWorldDir = worldDir;
        this.serverStarted = false;
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

        // 3. Take the lock in the source before touching anything. Held by someone else -> block the launch
        try {
            if (!claimLock(worldName)) {
                resetSession();
                return false;
            }
        } catch (IOException e) {
            // Source unreachable (e.g. the drive is offline): fail open and play the local copy without the lock
            LOGGER.error("Could not reach the lock for {} at {}, launching the local copy without it",
                    worldName, sourceWorld.describe(), e);
            return true;
        }

        // 4. Check both copies are the same world, then decide which to run.
        // Whenever the local copy is launched without being confirmed as the current shared world, the lock is
        // released: holding it would let a later push overwrite the source with the wrong world.
        WorldDecision decision;
        try {
            decision = registry.decide(worldDir, sourceWorld);
        } catch (IOException | MetadataValidationException e) {
            LOGGER.error("Could not compare {} against its source, launching the local copy without the lock",
                    worldName, e);
            releaseLock();
            return true;
        }

        switch (decision) {
            case UP_TO_DATE -> LOGGER.info("Local copy of {} is up to date, launching it", worldName);
            case PULL -> {
                if (!injectSource(worldDir, worldName)) {
                    releaseLock();
                }
            }
            case LOCAL_AHEAD -> LOGGER.warn("Local copy of {} has changes that were never pushed; launching it "
                    + "and keeping the lock so they can be pushed", worldName);
            case DIVERGED -> {
                // TODO let the player choose which copy to keep
                LOGGER.warn("Both the local copy of {} and its source changed since they last matched. Launching "
                        + "the local copy without the lock; neither copy is overwritten", worldName);
                releaseLock();
            }
            case DIFFERENT_WORLD -> {
                LOGGER.warn("The local {} and its source ({}) are different worlds. Launching the local copy "
                        + "without the lock; check the source location in sharedworlds.json",
                        worldName, sourceWorld.describe());
                releaseLock();
            }
            case CANNOT_COMPARE -> {
                LOGGER.warn("Could not compare {} with its source; launching the local copy without the lock",
                        worldName);
                releaseLock();
            }
        }
        return true;
    }


    /**
     * Claims the lock in sourceWorld for this client.
     *
     * @return true if this client now holds the lock, false if another client does
     * @throws IOException if the source's lock couldn't be read or written
     */
    private boolean claimLock(String worldName) throws IOException {
        if (lockManager.tryClaim(sourceWorld)) {
            this.holdsLock = true;
            return true;
        }

        Optional<Lock> holder = lockManager.readLock(sourceWorld);
        if (holder.isPresent() && lockManager.isOwnedByThisInstance(holder.get())) {
            // Left over from an earlier launch in this run whose release failed. It's ours, so keep it
            this.holdsLock = true;
            return true;
        }
        if (holder.isPresent() && lockManager.isOwnedByThisPlayer(holder.get())) {
            LOGGER.warn("{} has a lock you left at {} (a crash or a failed push); taking it back",
                    worldName, holder.get().getCreatedAt());
            if (lockManager.reclaim(sourceWorld)) {
                this.holdsLock = true;
                return true;
            }
        }

        // TODO show this on the lock screen instead of only logging it
        if (holder.isPresent()) {
            LOGGER.warn("{} is locked: {} has been hosting it since {}",
                    worldName, holder.get().getPlayerName(), holder.get().getCreatedAt());
        } else {
            LOGGER.warn("{} is locked, but its lock file couldn't be read", worldName);
        }
        LOGGER.warn("If nobody is playing {}, delete {} from its shared copy ({})",
                worldName, SharedWorldsFiles.WORLD_LOCK, sourceWorld.describe());
        return false;
    }

    /**
     * Releases the lock if this client holds it. Failures are logged: the lock is then left for its owner
     * (or, later, stale-lock handling) to clear.
     */
    private void releaseLock() {
        if (!this.holdsLock || this.sourceWorld == null) {
            return;
        }
        try {
            if (!lockManager.release(sourceWorld)) {
                LOGGER.error("Lock in {} was not released", sourceWorld.describe());
            }
        } catch (IOException e) {
            LOGGER.error("Could not release the lock in {}", sourceWorld.describe(), e);
        }
        this.holdsLock = false;
    }

    /**
     * Replaces the local copy with the source. Fails open: on any error the local copy is left as it was
     * (WorldInjector guarantees this) and launched instead.
     *
     * @return true if the source was injected
     */
    private boolean injectSource(Path worldDir, String worldName) {
        LOGGER.info("Source for {} is newer, injecting it from {}", worldName, sourceWorld.describe());
        boolean injected = false;
        try (WorldMaterialization materialized = sourceWorld.materialize()) {
            WorldInjector.InjectionResult result = injector.inject(materialized.worldDirectory(), worldDir);
            injected = true;
            LOGGER.info("Injected {}; previous local copy: {}", worldName,
                    result.backup().map(Path::toString).orElse("none"));
        } catch (IOException | RuntimeException e) {
            LOGGER.error("Could not inject {} from {}, launching the local copy",
                    worldName, sourceWorld.describe(), e);
        } finally {
            // The local folder may have been replaced, so its cached metadata is stale either way
            registry.forget(worldDir);
        }

        if (injected) {
            try {
                registry.markPulled(worldDir);
            } catch (IOException | MetadataValidationException e) {
                LOGGER.error("Could not update the metadata of {} after pulling it", worldName, e);
            }
        }
        return injected;
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
            // world), so the active session is stale. Drop it, releasing its lock.
            LOGGER.warn("Server started world {} but the active session is for {}; discarding stale session",
                    serverWorldDir, this.activeWorldDir);
            resetSession();
            return;
        }
        this.serverHasRun = true;

        if (this.holdsLock) {
            // Played as a shared world: flag it as unpushed (so a failed push is noticed next launch), and give it
            // a world ID if this is the first time it's been shared
            try {
                registry.markPlayed(this.activeWorldDir);
            } catch (IOException | MetadataValidationException e) {
                LOGGER.error("Could not update the metadata of {}", this.activeWorldDir, e);
            }
        }
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
        // Only push with the lock held: without it the local copy wasn't confirmed as the current shared world.
        // resetSession() below releases the lock, so the push must finish first.
        if (this.serverHasRun && this.holdsLock && !pushWorld()) {
            // The source may be half-pushed: leave the lock in place (forget it rather than release it)
            this.holdsLock = false;
        }
        resetSession();
        this.serverStarted = false;
    }

    /**
     * Pushes the active world to its source, in the order described in SharedWorldMetadataRegistry.beginPush.
     *
     * @return true if the lock can be released: the push finished, or failed before the source was changed.
     *         False if it failed partway, leaving the source with files whose version wasn't raised.
     */
    private boolean pushWorld() {
        String worldName = this.activeWorldDir.getFileName().toString();
        SharedWorldMetadata next;
        try {
            next = registry.beginPush(this.activeWorldDir, identity.playerName());
        } catch (IOException | MetadataValidationException e) {
            LOGGER.error("Could not start pushing {}; the source wasn't changed. It stays unpushed and will be "
                    + "pushed after it's next played", worldName, e);
            return true;
        }

        try {
            sourceWorld.pushFiles(this.activeWorldDir, WorldBlueprint.WORLD_ENTRIES);
            sourceWorld.writeMetadata(registry.serialize(next));
        } catch (IOException | MetadataValidationException | RuntimeException e) {
            LOGGER.error("Push of {} to {} failed partway. Keeping the lock so nobody pulls a half-pushed world; "
                    + "open the world again on this computer to retry", worldName, sourceWorld.describe(), e);
            return false;
        }

        try {
            registry.completePush(this.activeWorldDir, next);
        } catch (IOException | MetadataValidationException e) {
            // The source is complete; the next launch recognises this push by its ID (OWN_PUSH_LANDED)
            LOGGER.error("Pushed {} but could not record it locally; it will be recognised on the next launch",
                    worldName, e);
        }
        LOGGER.info("Pushed {} to {} (now version {})", worldName, sourceWorld.describe(), next.version());
        return true;
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
        releaseLock();
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
