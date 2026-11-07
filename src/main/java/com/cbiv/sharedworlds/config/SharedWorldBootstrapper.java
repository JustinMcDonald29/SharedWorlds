package com.cbiv.sharedworlds.config;

import com.cbiv.sharedworlds.fs.WorldSourceType;
import com.cbiv.sharedworlds.common.SharedWorldsSchemas;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;


/**
 * One-shot bootstrapper responsible for bringing shared world metadata
 * into a valid baseline state at client startup.
 *
 * This class is NOT used at runtime after initialization.
 */
public final class SharedWorldBootstrapper {

    private static final Logger LOGGER =
            LoggerFactory.getLogger("SharedWorldBootstrapper");

    private final Gson gson;

    public SharedWorldBootstrapper() {
        this.gson = new GsonBuilder()
                .registerTypeAdapter(WorldSourceType.class, new WorldSourceTypeAdapter())
                .registerTypeHierarchyAdapter(Path.class, new PathTypeAdapter())
                .setPrettyPrinting()
                .create();
    }

    /**
     * Performs initial configuration bootstrap:
     * - Loads global config
     * - Validates schema compatibility
     * - Warns about configured worlds that don't exist locally
     *
     * It never creates sharedworld.meta.json: a world's identity comes from its source (first pull) or is created
     * the first time it is played as a shared world, never from its folder name (decision 4).
     *
     * This method is intended to be called once during client initialization.
     *
     * @return the loaded global config, for use at runtime
     */
    public GlobalSharedWorldConfig bootstrap(Path globalConfigPath, Path savesDir) throws IOException {
        LOGGER.info("Bootstrapping SharedWorlds configuration");

        GlobalSharedWorldConfig config = loadGlobalConfig(globalConfigPath);

        for (GlobalSharedWorldConfig.SharedWorldEntry entry : config.worlds()) {
            Path worldDir = savesDir.resolve(entry.local().name());
            bootstrapWorld(worldDir, entry);
        }

        LOGGER.info("SharedWorlds bootstrap completed");
        return config;
    }

    /**
     * Loads and validates the global shared worlds configuration file.
     */
    private GlobalSharedWorldConfig loadGlobalConfig(Path configPath) throws IOException {
        if (!Files.exists(configPath)) {
            throw new IOException("Global config file does not exist: " + configPath);
        }

        try (Reader reader = Files.newBufferedReader(configPath)) {
            GlobalSharedWorldConfig config =
                    gson.fromJson(reader, GlobalSharedWorldConfig.class);

            if (config.schemaVersion() != SharedWorldsSchemas.GLOBAL_CONFIG) {
                throw new IllegalStateException(
                        "Unsupported global config schema: " + config.schemaVersion()
                );
            }

            LOGGER.info(
                    "Loaded global config (schema={}, worlds={})",
                    config.schemaVersion(),
                    config.worlds().size()
            );

            return config;
        }
    }

    /**
     * Checks a single world declared in the global config.
     */
    private void bootstrapWorld(
            Path worldDir,
            GlobalSharedWorldConfig.SharedWorldEntry entry
    ) throws IOException {

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
