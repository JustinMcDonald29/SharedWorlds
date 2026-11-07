package com.cbiv.sharedworlds.config;

import com.cbiv.sharedworlds.common.SharedWorldsSchemas;
import com.cbiv.sharedworlds.common.WorldIds;

import java.time.Instant;
import java.util.UUID;

/**
 * Contents of sharedworld.meta.json: describes the WORLD and travels with it (pulled with the world, written to
 * the source by a push). Anything about this computer belongs in sharedworlds.json instead (decision 3).
 *
 * @param worldId       permanent ID (UUIDv7), created once when the world is first played as a shared world
 * @param version       goes up by 1 on every push. A local copy's version is the one it last pulled or pushed
 * @param unpushed      true once the world has been played on this computer since its last pull/push; cleared
 *                      by a successful push (and always false in the source)
 * @param lastPushId    unique ID of the push that produced this version, null before the first push
 * @param lastPushedAt  when the last push happened (ISO-8601), null before the first push
 * @param lastPushedBy  who made the last push, null before the first push
 * @param pendingPushId LOCAL ONLY: the push this computer has started but not yet recorded as finished.
 *                      Lets the next launch recognise a push that reached the source just before a crash.
 *                      Never written to the source.
 */
public record SharedWorldMetadata(
        int schemaVersion,
        UUID worldId,
        long version,
        boolean unpushed,
        UUID lastPushId,
        String lastPushedAt,
        String lastPushedBy,
        UUID pendingPushId
) {

    /** A brand-new identity, for a world that has never been shared before. */
    public static SharedWorldMetadata createNew() {
        return new SharedWorldMetadata(SharedWorldsSchemas.WORLD_METADATA, WorldIds.newWorldId(), 0, false,
                null, null, null, null);
    }

    public SharedWorldMetadata withUnpushed(boolean unpushed) {
        return new SharedWorldMetadata(schemaVersion, worldId, version, unpushed,
                lastPushId, lastPushedAt, lastPushedBy, pendingPushId);
    }

    public SharedWorldMetadata withPendingPush(UUID pushId) {
        return new SharedWorldMetadata(schemaVersion, worldId, version, unpushed,
                lastPushId, lastPushedAt, lastPushedBy, pushId);
    }

    /**
     * The metadata a push writes to the source (and, once finished, to the local copy): the next version, a new
     * push ID, nothing unpushed and nothing pending.
     */
    public SharedWorldMetadata nextPush(String pushedBy, Instant now) {
        return new SharedWorldMetadata(schemaVersion, worldId, version + 1, false,
                UUID.randomUUID(), now.toString(), pushedBy, null);
    }
}
