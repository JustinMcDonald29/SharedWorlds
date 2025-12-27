package com.cbiv.sharedworlds.lock;

import java.time.Instant;
import java.util.UUID;

public final class Lock {

    // World Identity
    //private final String worldName;

    //Instance Identity
    private final UUID instanceId;

    // Player Identity
    private final UUID playerUuid;
    private final String playerName;

    // Metadata
    private final Instant createdAt;
    private final String mcVersion;
    private final String modVersion;

    public Lock(
            UUID instanceId,
            UUID playerUuid,
            String playerName,
            Instant createdAt,
            String mcVersion,
            String modVersion
    ){
        this.instanceId = instanceId;
        this.playerUuid = playerUuid;
        this.playerName = playerName;
        this.createdAt = createdAt;
        this.mcVersion = mcVersion;
        this.modVersion = modVersion;
    }

    public UUID getInstanceId() { return instanceId; }
    public UUID getPlayerUuid() { return playerUuid; }
    public String getPlayerName() { return playerName; }
    public Instant getCreatedAt() { return createdAt; }
    public String getMcVersion() { return mcVersion; }
    public String getModVersion() { return modVersion; }
}
