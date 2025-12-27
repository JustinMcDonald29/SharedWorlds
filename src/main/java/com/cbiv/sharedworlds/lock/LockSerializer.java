package com.cbiv.sharedworlds.lock;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.google.gson.JsonElement;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.time.Instant;
import net.minecraft.util.Uuids;

public final class LockSerializer {

    private LockSerializer() {}

    public static final Codec<Lock> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Uuids.CODEC.fieldOf("instanceId").forGetter(Lock::getInstanceId),
            Uuids.CODEC.fieldOf("playerUuid").forGetter(Lock::getPlayerUuid),
            Codec.STRING.fieldOf("playerName").forGetter(Lock::getPlayerName),
            instantCodec().fieldOf("createdAt").forGetter(Lock::getCreatedAt),
            Codec.STRING.fieldOf("mcVersion").forGetter(Lock::getMcVersion),
            Codec.STRING.fieldOf("modVersion").forGetter(Lock::getModVersion)
    ).apply(instance, Lock::new));

    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static Codec<Instant> instantCodec() {
        return Codec.STRING.comapFlatMap(
                s -> {
                    try {
                        return DataResult.success(Instant.parse(s));
                    } catch (Exception e) {
                        return DataResult.error(() -> "Invalid Instant: " + s);
                    }
                },
                Instant::toString
        );
    }

    public static DataResult<JsonElement> toJson(Lock lock) {
        return CODEC.encodeStart(JsonOps.INSTANCE, lock);
    }

    public static DataResult<Lock> fromJson(JsonElement json) {
        return CODEC.parse(JsonOps.INSTANCE, json);
    }
}
