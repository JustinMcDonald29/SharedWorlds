package com.cbiv.sharedworlds.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;


/**
 * One-shot bootstrapper responsible for bringing shared world metadata
 * into a valid baseline state at client startup.
 *
 * This class is NOT used at runtime after initialization. Loading and saving sharedworlds.json is
 * SharedWorldConfigStore's job.
 */
public final class SharedWorldBootstrapper {

    private static final Logger LOGGER =
            LoggerFactory.getLogger("SharedWorldBootstrapper");

    /**
     * Checks the worlds declared in the loaded config:
     * - Warns about configured worlds that don't exist locally
     *
     * It never creates sharedworld.meta.json: a world's identity comes from its source (first pull) or is created
     * the first time it is played as a shared world, never from its folder name (decision 4).
     *
     * This method is intended to be called once during client initialization.
     */
    public void bootstrap(GlobalSharedWorldConfig config, Path savesDir) {
        LOGGER.info("Bootstrapping SharedWorlds ({} shared worlds)", config.worlds().size());

        for (GlobalSharedWorldConfig.SharedWorldEntry entry : config.worlds()) {
            if (entry.local() == null || entry.local().name() == null) {
                LOGGER.warn("Shared world entry '{}' has no local world name; it is ignored", entry.id());
                continue;
            }
            bootstrapWorld(savesDir.resolve(entry.local().name()), entry);
        }

        LOGGER.info("SharedWorlds bootstrap completed");
    }

    /**
     * Checks a single world declared in the global config.
     */
    private void bootstrapWorld(Path worldDir, GlobalSharedWorldConfig.SharedWorldEntry entry) {
        if (!Files.exists(worldDir)) {
            LOGGER.warn(
                    "Configured shared world '{}' does not exist at {}",
                    entry.id(),
                    worldDir
            );
            // TODO initial setup: pull the world from its source if it's missing locally
        }
    }
}
