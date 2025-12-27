package com.cbiv.sharedworlds;

import java.util.UUID;
public record ClientIdentity(
        UUID instanceId,
        UUID playerUuid,
        String playerName,
        String mcVersion,
        String modVersion
) {}
