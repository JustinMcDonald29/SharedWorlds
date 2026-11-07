package com.cbiv.sharedworlds.config;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Reads the fields SharedWorlds compares from a compressed level.dat stream.
 */
public final class LevelDatReader {

    private static final Logger LOGGER = LoggerFactory.getLogger("LevelDatReader");

    private LevelDatReader() {}

    /**
     * @param description used in the log if reading fails
     * @return the level's info, or LevelInfo.unknown() if it couldn't be read
     */
    public static LevelInfo read(InputStream levelDat, String description) {
        try (levelDat) {
            NbtCompound root = NbtIo.readCompressed(levelDat, NbtSizeTracker.forLevel());
            Optional<NbtCompound> data = root.getCompound("Data");

            OptionalLong seed = data
                    .flatMap(d -> d.getCompound("WorldGenSettings"))
                    .flatMap(settings -> settings.getLong("seed"))
                    .map(OptionalLong::of)
                    .orElse(OptionalLong.empty());
            Instant lastPlayed = data
                    .flatMap(d -> d.getLong("LastPlayed"))
                    .map(Instant::ofEpochMilli)
                    .orElse(Instant.EPOCH);

            return new LevelInfo(seed, lastPlayed);
        } catch (IOException | RuntimeException e) {
            LOGGER.error("Failed to read level.dat of {}", description, e);
            return LevelInfo.unknown();
        }
    }
}
