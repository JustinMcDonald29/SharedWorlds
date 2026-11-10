package com.cbiv.sharedworlds;

import com.cbiv.sharedworlds.config.SharedWorldBootstrapper;
import com.cbiv.sharedworlds.config.SharedWorldConfigStore;
import com.cbiv.sharedworlds.config.SharedWorldMetadataRegistry;
import com.cbiv.sharedworlds.fs.WorldInjector;
import com.cbiv.sharedworlds.service.SharedWorldsService;
import com.cbiv.sharedworlds.ui.SelectWorldScreenHook;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.client.MinecraftClient;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import java.util.UUID;

public class SharedWorldsClient implements ClientModInitializer{

    private static SharedWorldsClient INSTANCE;

    private WorldRuntimeCoordinator coordinator;
    private SharedWorldsService service;

    private static final Logger LOGGER =
            LoggerFactory.getLogger("SharedWorldsClient");

    public SharedWorldsClient(){
    }

    @Override
    public void onInitializeClient() {
        Path savesDir = resolveSavesDir();
        SharedWorldConfigStore configStore = SharedWorldConfigStore.load(resolveConfigPath());
        bootstrap(configStore, savesDir);
        INSTANCE = this;

        ClientIdentity identity = createIdentity();
        // One registry for the whole client: the launch code and the in-game actions must share its cache
        SharedWorldMetadataRegistry registry = new SharedWorldMetadataRegistry();
        // Staging and backup folders sit next to saves/ (same drive, so the injector's renames are atomic)
        // but outside it, so they never show up in the world list.
        WorldInjector injector = new WorldInjector(FabricLoader.getInstance().getGameDir().resolve("sharedworlds"));

        this.coordinator = new WorldRuntimeCoordinator(identity, configStore, registry, injector);
        this.service = new SharedWorldsService(savesDir, configStore, registry, injector, identity.playerName());

        SelectWorldScreenHook.register();
    }

    public static WorldRuntimeCoordinator coordinator() {
        return INSTANCE.coordinator;
    }

    public static SharedWorldsService service() {
        return INSTANCE.service;
    }

    /**
     * Runs the one-shot bootstrapper. Failures are logged and never stop the game.
     */
    private void bootstrap(SharedWorldConfigStore configStore, Path savesDir){
        try {
            new SharedWorldBootstrapper().bootstrap(configStore.current(), savesDir);
        } catch (RuntimeException e) {
            LOGGER.error("SharedWorlds failed to bootstrap", e);
        }
    }

    private Path resolveConfigPath(){
        return FabricLoader.getInstance().getConfigDir().resolve("sharedworlds.json");
    }

    private Path resolveSavesDir(){
        return FabricLoader.getInstance().getGameDir().resolve("saves");
    }

    private ClientIdentity createIdentity() {
        MinecraftClient client = MinecraftClient.getInstance();

        UUID instanceId = UUID.randomUUID();
        UUID playerUuid = client.getSession().getUuidOrNull();
        String playerName = client.getSession().getUsername();
        String mcVersion = SharedConstants.getGameVersion().toString();
        String modVersion = FabricLoader.getInstance()
                .getModContainer("sharedworlds")
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");

        return new ClientIdentity(
                instanceId,
                playerUuid,
                playerName,
                mcVersion,
                modVersion
        );
    }
}
