package com.cbiv.sharedworlds.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Decides which copy of a shared world to run. Pure logic: callers read the metadata and level.dat files.
 *
 * Identity first, then version:
 *  - Both copies have an ID: the IDs must match. Then the version counter and the local unpushed flag decide.
 *  - Otherwise (first pull, or worlds from before world IDs existed): the seeds must match, then
 *    level.dat LastPlayed decides.
 */
public final class SharedWorldComparator {

    private static final Logger LOGGER = LoggerFactory.getLogger("SharedWorldComparator");

    private SharedWorldComparator() {}

    public static WorldDecision decide(Optional<SharedWorldMetadata> local, Optional<SharedWorldMetadata> source,
                                       LevelInfo localLevel, LevelInfo sourceLevel) {
        if (local.isPresent() && source.isPresent()) {
            return byVersion(local.get(), source.get());
        }

        // At least one copy has no ID: the seed is the only identity check available
        if (localLevel.seed().isEmpty() || sourceLevel.seed().isEmpty()) {
            LOGGER.warn("Could not read the seed of both copies, so they can't be confirmed to be the same world");
            return WorldDecision.CANNOT_COMPARE;
        }
        if (localLevel.seed().getAsLong() != sourceLevel.seed().getAsLong()) {
            LOGGER.warn("Seeds differ (local={}, source={}): these are different worlds",
                    localLevel.seed().getAsLong(), sourceLevel.seed().getAsLong());
            return WorldDecision.DIFFERENT_WORLD;
        }

        if (local.isEmpty() && source.isPresent()) {
            // First pull (decision 4): the local copy takes the source's identity
            LOGGER.info("Local copy has no world ID and the seeds match: first pull from the source");
            return WorldDecision.PULL;
        }

        // The source has no ID (never pushed with world IDs): fall back to level.dat LastPlayed
        boolean sourceNewer = sourceLevel.lastPlayed().isAfter(localLevel.lastPlayed());
        boolean playedHere = local.isPresent() && local.get().unpushed();
        LOGGER.info("No world ID in the source, comparing LastPlayed: local={} source={} sourceNewer={} playedHere={}",
                localLevel.lastPlayed(), sourceLevel.lastPlayed(), sourceNewer, playedHere);
        if (playedHere) {
            return sourceNewer ? WorldDecision.DIVERGED : WorldDecision.LOCAL_AHEAD;
        }
        return sourceNewer ? WorldDecision.PULL : WorldDecision.UP_TO_DATE;
    }

    private static WorldDecision byVersion(SharedWorldMetadata local, SharedWorldMetadata source) {
        if (!local.worldId().equals(source.worldId())) {
            LOGGER.warn("World IDs differ (local={}, source={}): these are different worlds",
                    local.worldId(), source.worldId());
            return WorldDecision.DIFFERENT_WORLD;
        }

        long localVersion = local.version();
        long sourceVersion = source.version();
        LOGGER.info("Comparing versions: local={} (unpushed={}) source={}",
                localVersion, local.unpushed(), sourceVersion);

        if (sourceVersion > localVersion) {
            if (!local.unpushed()) {
                return WorldDecision.PULL;
            }
            if (sourceVersion == localVersion + 1 && local.pendingPushId() != null
                    && local.pendingPushId().equals(source.lastPushId())) {
                LOGGER.info("Source version {} is this computer's own push {}, which finished but wasn't recorded",
                        sourceVersion, source.lastPushId());
                return WorldDecision.OWN_PUSH_LANDED;
            }
            return WorldDecision.DIVERGED;
        }
        if (sourceVersion < localVersion) {
            // The source went backwards (e.g. restored from a backup). Never pull an older version over a newer one.
            LOGGER.warn("Source version {} is older than the local copy's {}", sourceVersion, localVersion);
            return WorldDecision.LOCAL_AHEAD;
        }
        return local.unpushed() ? WorldDecision.LOCAL_AHEAD : WorldDecision.UP_TO_DATE;
    }
}
