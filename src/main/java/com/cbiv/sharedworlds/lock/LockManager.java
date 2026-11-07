package com.cbiv.sharedworlds.lock;

import com.cbiv.sharedworlds.ClientIdentity;
import com.cbiv.sharedworlds.fs.WorldSource;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Owns what a lock MEANS: building one for this client, parsing it, whether it's ours, whether it's stale.
 * Where the lock is stored is the WorldSource's job: it only reads, creates and deletes the raw lock file,
 * so checking a lock never requires materializing the world.
 */
public final class LockManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("LockManager");

    // configurable later; to be replaced by a heartbeat (decision 2)
    private static final Duration STALE_THRESHOLD = Duration.ofHours(6);

    private final UUID instanceId;
    private final String playerName;
    private final UUID playerUuid;
    private final String mcVersion;
    private final String modVersion;

    public LockManager(ClientIdentity identity) {
        this.instanceId = identity.instanceId();
        this.playerUuid = identity.playerUuid();
        this.playerName = identity.playerName();
        this.mcVersion = identity.mcVersion();
        this.modVersion = identity.modVersion();
        LOGGER.info("LockManager created");
    }

    /* ------------------------------------------------------------
       Public API
       ------------------------------------------------------------ */

    /**
     * Claims the source's lock for this client.
     *
     * @return true if this client now holds the lock, false if a lock already exists
     * @throws IOException if the source can't be reached or the lock can't be written
     */
    public boolean tryClaim(WorldSource source) throws IOException {
        Lock lock = new Lock(instanceId, playerUuid, playerName, Instant.now(), mcVersion, modVersion);
        boolean claimed = source.tryCreateLock(serialize(lock));
        if (claimed) {
            LOGGER.info("Claimed lock in {}", source.describe());
        }
        return claimed;
    }

    /**
     * Reads the source's lock.
     *
     * @return the lock, or empty if there is none OR it exists but can't be parsed. Use tryClaim to find out
     *         whether a world is free; this is for showing who holds it.
     */
    public Optional<Lock> readLock(WorldSource source) throws IOException {
        Optional<InputStream> stream = source.openLock();
        if (stream.isEmpty()) {
            return Optional.empty();
        }
        return parse(stream.get(), source);
    }

    /**
     * Deletes the source's lock, but only if this client holds it.
     *
     * @return true if no lock of ours is left, false if the lock belongs to someone else or can't be read
     */
    public boolean release(WorldSource source) throws IOException {
        Optional<InputStream> stream = source.openLock();
        if (stream.isEmpty()) {
            LOGGER.warn("No lock to release in {}", source.describe());
            return true;
        }
        Optional<Lock> lock = parse(stream.get(), source);
        if (lock.isEmpty() || !isOwnedByThisInstance(lock.get())) {
            LOGGER.warn("Refusing to delete a lock in {} not owned by this instance", source.describe());
            return false;
        }
        source.deleteLock();
        LOGGER.info("Released lock in {}", source.describe());
        return true;
    }

    public boolean isOwnedByThisInstance(Lock lock) {
        return instanceId.equals(lock.getInstanceId());
    }

    public boolean isStale(Lock lock) {
        return Duration.between(lock.getCreatedAt(), Instant.now())
                .compareTo(STALE_THRESHOLD) > 0;
    }

    /* ------------------------------------------------------------
       Internals
       ------------------------------------------------------------ */

    private static Optional<Lock> parse(InputStream stream, WorldSource source) throws IOException {
        try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            JsonElement json = JsonParser.parseReader(reader);
            return LockSerializer.fromJson(json).result();
        } catch (JsonParseException e) {
            LOGGER.warn("Lock in {} could not be parsed", source.describe(), e);
            return Optional.empty();
        }
    }

    private static byte[] serialize(Lock lock) throws IOException {
        Optional<JsonElement> json;
        try {
            json = LockSerializer.toJson(lock).result();
        } catch (RuntimeException e) {
            // e.g. no player UUID (getUuidOrNull() returned null)
            throw new IOException("Could not serialize lock", e);
        }
        if (json.isEmpty()) {
            throw new IOException("Could not serialize lock");
        }
        return LockSerializer.GSON.toJson(json.get()).getBytes(StandardCharsets.UTF_8);
    }
}
