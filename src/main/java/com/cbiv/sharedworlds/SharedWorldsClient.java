package com.cbiv.sharedworlds;

import com.cbiv.sharedworlds.config.SharedWorldBootstrapper;
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

    private static final Logger LOGGER =
            LoggerFactory.getLogger("SharedWorldsClient");

    public SharedWorldsClient(){
    }

    @Override
    public void onInitializeClient() {
        bootstrap();
        INSTANCE = this;
        this.coordinator = createCoordinator();
    }

    public static WorldRuntimeCoordinator coordinator() {
        return INSTANCE.coordinator;
    }

    private void bootstrap(){
        try {
            Path configPath = resolveConfigPath();
            Path savesDir = resolveSavesDir();

            new SharedWorldBootstrapper()
                    .bootstrap(configPath, savesDir);

        } catch (Exception e) {
            LOGGER.error("SharedWorlds failed to bootstrap", e);
        }

    }

    private Path resolveConfigPath(){
        return FabricLoader.getInstance().getConfigDir().resolve("sharedworlds.json");
    }

    private Path resolveSavesDir(){
        return FabricLoader.getInstance().getGameDir().resolve("saves");
    }

    private WorldRuntimeCoordinator createCoordinator() {
        MinecraftClient client = MinecraftClient.getInstance();

        UUID instanceId = UUID.randomUUID();
        UUID playerUuid = client.getSession().getUuidOrNull();
        String playerName = client.getSession().getUsername();
        String mcVersion = SharedConstants.getGameVersion().toString();
        String modVersion = FabricLoader.getInstance()
                .getModContainer("sharedworlds")
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown)");

        ClientIdentity identity = new ClientIdentity(
                instanceId,
                playerUuid,
                playerName,
                mcVersion,
                modVersion
        );

        return new WorldRuntimeCoordinator(identity);
    }
}
