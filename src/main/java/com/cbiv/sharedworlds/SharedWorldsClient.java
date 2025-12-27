package com.cbiv.sharedworlds;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.client.MinecraftClient;


import java.util.UUID;

public class SharedWorldsClient implements ClientModInitializer{

    private static SharedWorldsClient INSTANCE;

    private WorldRuntimeCoordinator coordinator;

    public SharedWorldsClient(){
    }

    @Override
    public void onInitializeClient() {
        INSTANCE = this;

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

        this.coordinator = new WorldRuntimeCoordinator(identity);
    }

    public static WorldRuntimeCoordinator coordinator() {
        return INSTANCE.coordinator;
    }
}
