package com.cbiv.sharedworlds.config;

import com.cbiv.sharedworlds.fs.WorldSource;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class SharedWorldComparator {

    private static final Logger LOGGER = LoggerFactory.getLogger("SharedWorldComparator");
    // Placeholder version written by SharedWorldMetadata.initial(); carries no ordering information
    private static final String UNKNOWN_VERSION = "unknown";

    /**
     * Determines if the source world is newer than the local world.
     *
     * Cascade:
     *  1. worldVersion, if both sides have a real (non-"unknown") version
     *  2. source lastPushed vs local lastPulled, if both exist and differ
     *  3. LastPlayed in level.dat (also used when source metadata is missing)
     */
    public static boolean isSourceNewer(Path localWorldDir, SharedWorldMetadata localMetadata,
                                        WorldSource source, SharedWorldMetadata sourceMetadata) {

        if (sourceMetadata != null && sourceMetadata.state() != null
                && localMetadata != null && localMetadata.state() != null) {
            String sourceVersion = sourceMetadata.state().worldVersion();
            String localVersion = localMetadata.state().worldVersion();

            // 1. Real versions on both sides
            if (isKnownVersion(sourceVersion) && isKnownVersion(localVersion)) {
                // TODO replace with a version counter that only increases, so "different" can't be mistaken for "newer"
                LOGGER.debug("Comparing WorldVersions: Local={} vs Source={}", localVersion, sourceVersion);
                return !sourceVersion.equals(localVersion);
            }

            // 2. Push/pull timestamps. Identical timestamps carry no ordering information, so fall through.
            String sourcePushed = sourceMetadata.state().lastPushed();
            String localPulled = localMetadata.state().lastPulled();
            if (sourcePushed != null && !sourcePushed.isEmpty()
                    && localPulled != null && !localPulled.isEmpty()) {
                Instant sourceLast = parseTimestamp(sourcePushed, Instant.EPOCH);
                Instant localLast = parseTimestamp(localPulled, Instant.EPOCH);
                if (!sourceLast.equals(localLast)) {
                    LOGGER.debug("Fallback 1 Comparing Push/Pull: LocalLastPulled={} vs SourceLastPushed={}",
                            localPulled, sourcePushed);
                    return sourceLast.isAfter(localLast);
                }
            }
        }

        // 3. level.dat LastPlayed
        LOGGER.debug("Fallback 2 Comparing level.dat lastPlayed");
        Instant sourceLevelDat = readSourceLastPlayed(source);
        Instant localLevelDat = readLocalLastPlayed(localWorldDir);
        boolean result = sourceLevelDat.isAfter(localLevelDat);
        LOGGER.info("Fallback 2 comparison result: localLastPlayed={} sourceLastPlayed={} sourceIsNewer={}",
                localLevelDat,
                sourceLevelDat,
                result);
        return result;
    }

    private static boolean isKnownVersion(String version) {
        return version != null && !version.isEmpty() && !UNKNOWN_VERSION.equals(version);
    }

    /**
     * Helper to parse timestamps, fallback to Instant.EPOCH if invalid.
     */
    private static Instant parseTimestamp(String timestamp, Instant fallback) {
        if (timestamp == null || timestamp.isEmpty()) return fallback;
        try {
            return Instant.parse(timestamp);
        } catch (Exception e) {
            return fallback;
        }
    }

    /**
     * Reads LastPlayed from the local world's level.dat.
     */
    private static Instant readLocalLastPlayed(Path worldDir) {
        Path levelDatPath = worldDir.resolve("level.dat");
        if (!Files.exists(levelDatPath)) {
            LOGGER.error("Failed to find level.dat for world {}", worldDir);
            return Instant.EPOCH;
        }
        try (InputStream is = Files.newInputStream(levelDatPath)) {
            return readLastPlayed(is);
        } catch (IOException e) {
            LOGGER.error("Failed to read level.dat for world {}", worldDir, e);
            return Instant.EPOCH;
        }
    }

    /**
     * Reads LastPlayed from the source world's level.dat, through the WorldSource.
     */
    private static Instant readSourceLastPlayed(WorldSource source) {
        try (InputStream is = source.openLevelDat()) {
            return readLastPlayed(is);
        } catch (IOException e) {
            LOGGER.error("Failed to read level.dat for source {}", source.describe(), e);
            return Instant.EPOCH;
        }
    }

    /**
     * Reads the LastPlayed timestamp from a compressed level.dat stream.
     * Uses Optional to handle missing compounds/fields safely.
     */
    private static Instant readLastPlayed(InputStream levelDat) throws IOException {
        NbtCompound root = NbtIo.readCompressed(levelDat, NbtSizeTracker.forLevel());
        Optional<NbtCompound> dataOpt = root.getCompound("Data");
        Optional<Long> lastPlayedOpt = dataOpt.flatMap(data -> data.getLong("LastPlayed"));
        return lastPlayedOpt.map(Instant::ofEpochMilli).orElse(Instant.EPOCH);
    }
}
