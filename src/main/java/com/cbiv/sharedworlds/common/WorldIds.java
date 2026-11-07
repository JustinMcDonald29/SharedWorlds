package com.cbiv.sharedworlds.common;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * Creates permanent world IDs.
 *
 * Minecraft gives worlds no unique ID (level.dat has no UUID or creation time), so SharedWorlds creates one the
 * first time a world is played as a shared world and stores it in sharedworld.meta.json.
 *
 * IDs are UUIDv7 (RFC 9562): the first 48 bits are the creation time in Unix milliseconds, the rest is random.
 * So an ID records when the world was first shared, sorts by that time, and is unique.
 */
public final class WorldIds {

    private static final SecureRandom RANDOM = new SecureRandom();

    private WorldIds() {}

    public static UUID newWorldId() {
        return newWorldId(System.currentTimeMillis());
    }

    static UUID newWorldId(long unixMillis) {
        long randA = RANDOM.nextLong();
        long randB = RANDOM.nextLong();
        long msb = (unixMillis << 16)          // 48-bit timestamp
                | 0x7000L                      // version 7
                | (randA & 0x0FFFL);           // 12 random bits
        long lsb = 0x8000000000000000L         // variant 10
                | (randB & 0x3FFFFFFFFFFFFFFFL); // 62 random bits
        return new UUID(msb, lsb);
    }

    /** When the world was first shared, for UUIDv7 IDs. */
    public static long createdAtMillis(UUID worldId) {
        return worldId.getMostSignificantBits() >>> 16;
    }
}
