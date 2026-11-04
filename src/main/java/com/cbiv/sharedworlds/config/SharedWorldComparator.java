package com.cbiv.sharedworlds.config;

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
    /**
     * Determines if the source world is newer than the local world.
     * Falls back to reading LastPlayed in level.dat if metadata is missing.
     */
    public static boolean isSourceNewer(Path localWorldDir, SharedWorldMetadata localMetadata,
                                        Path sourceWorldDir, SharedWorldMetadata sourceMetadata) {

        // If source metadata exists, use its state for comparison
        if (sourceMetadata != null) {
            // Simple example: compare worldVersion first
            if (sourceMetadata.state() != null && localMetadata.state() != null) {
                String sourceVersion = sourceMetadata.state().worldVersion();
                String localVersion = localMetadata.state().worldVersion();
                String sourcePushed = sourceMetadata.state().lastPushed();
                String localPulled = localMetadata.state().lastPulled();
                if (sourceVersion != null && !sourceVersion.isEmpty() &&
                        localVersion != null && !localVersion.isEmpty()) {
                    // You could expand this to do semantic version comparison if needed
                    LOGGER.debug("Comparing WorldVersions: Local={} vs Source={}",localVersion, sourceVersion);
                    return !sourceVersion.equals(localVersion);
                // If no valid version, try lastPushed / lastPulled as fallback
                } else if(sourcePushed != null && !sourcePushed.isEmpty() &&
                        localPulled != null && !localPulled.isEmpty()) {
                    Instant sourceLast = parseTimestamp(sourcePushed, Instant.EPOCH);
                    Instant localLast = parseTimestamp(localPulled, Instant.EPOCH);
                    LOGGER.debug("Fallback 1 Comparing Push/Pull: LocalLastPulled={} vs SourceLastPushed={}",
                            localPulled, sourcePushed);
                    return sourceLast.isAfter(localLast);
                }
            }
        }
        LOGGER.debug("Fallback 2 Comparing level.dat lastPlayed");
        // If source metadata is missing, fallback to level.dat comparison
        Instant sourceLevelDat = readLevelDatLastPlayed(sourceWorldDir);
        Instant localLevelDat = readLevelDatLastPlayed(localWorldDir);
        boolean result = sourceLevelDat.isAfter(localLevelDat);
        LOGGER.info("Fallback 2 comparison result: localLastPlayed={} sourceLastPlayed={} sourceIsNewer={}",
                localLevelDat,
                sourceLevelDat,
                result);
        return result;
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
     * Reads the LastPlayed timestamp from level.dat in the given world folder.
     * Uses Optional to handle missing compounds/fields safely.
     */
    private static Instant readLevelDatLastPlayed(Path worldDir) {
        Path levelDatPath = worldDir.resolve("level.dat");
        if (!Files.exists(levelDatPath)) {
            LOGGER.error("Failed to find level.dat for world {}", worldDir);
            return Instant.EPOCH;
        }

        try (InputStream is = Files.newInputStream(levelDatPath)) {
            NbtCompound root = NbtIo.readCompressed(is, NbtSizeTracker.forLevel());

            Optional<NbtCompound> dataOpt = root.getCompound("Data");
            Optional<Long> lastPlayedOpt = dataOpt.flatMap(data -> data.getLong("LastPlayed"));

            return lastPlayedOpt.map(Instant::ofEpochMilli).orElse(Instant.EPOCH);

        } catch (IOException e) {
            LOGGER.error("Failed to read level.dat for world{}", worldDir);
            return Instant.EPOCH;
        }
    }
}
