package com.cbiv.sharedworlds.config;

import java.time.Instant;
import java.util.OptionalLong;

/**
 * The parts of a world's level.dat that SharedWorlds compares.
 *
 * @param seed       the world seed (Data.WorldGenSettings.seed), empty if level.dat couldn't be read
 * @param lastPlayed Data.LastPlayed, or the epoch if unknown
 */
public record LevelInfo(OptionalLong seed, Instant lastPlayed) {

    public static LevelInfo unknown() {
        return new LevelInfo(OptionalLong.empty(), Instant.EPOCH);
    }
}
