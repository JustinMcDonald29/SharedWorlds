package com.cbiv.sharedworlds.lock;

import com.cbiv.sharedworlds.ClientIdentity;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.WorldSavePath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static java.nio.file.StandardCopyOption.*;

public final class LockManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("LockManager");
    private static final String LOCK_FILE_NAME = "lock.json";

    // configurable later
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
    }

    /* ------------------------------------------------------------
       Public API
       ------------------------------------------------------------ */
    public Optional<Lock> readLock(Path worldDir) {
        Path lockPath = lockPath(worldDir);
        if (!Files.exists(lockPath)){
            return Optional.empty();
        }

        try (Reader reader = Files.newBufferedReader(lockPath)) {
            JsonElement json = JsonParser.parseReader(reader);
            return LockSerializer.fromJson(json).result();
        } catch (Exception e) {
            LOGGER.error("Failed to read lock file {}", lockPath, e);
            return Optional.empty();
        }
    }

    public boolean isLocked(Path worldDir) {
        return Files.exists(lockPath(worldDir));
    }

    public boolean createLock(Path worldDir) {
        Path lockPath = lockPath(worldDir);

        if(Files.exists(lockPath)) {
            LOGGER.warn("Lock already exists: {}", lockPath);
            return false;
        }

        Lock lock = new Lock(
                instanceId,
                playerUuid,
                playerName,
                Instant.now(),
                mcVersion,
                modVersion
        );

        try {
            writeAtomically(lockPath,lock);
            LOGGER.info("Created lock for world {}", worldDir);
            return true;
        } catch (IOException e) {
            LOGGER.error("Failed to create lock file", e);
            return false;
        }
    }

    public boolean deleteLock(Path worldDir) {
        Path lockPath = lockPath(worldDir);

        Optional<Lock> lock = readLock(worldDir);
        if(lock.isEmpty()) {
            return true;
        }

        if (!isOwnedByThisInstance(lock.get())){
            LOGGER.warn("Refusing to delete lock not owned by this instance");
            return false;
        }

        try {
            Files.deleteIfExists(lockPath);
            LOGGER.info("Deleted lock {}", lockPath);
            return true;
        } catch (IOException e) {
            LOGGER.error("Failed to delete lock file", e);
            return false;
        }
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
    private Path lockPath(Path worldDir) {
        return worldDir.resolve(LOCK_FILE_NAME);
    }

    private void writeAtomically(Path lockPath, Lock lock) throws IOException {
        Path tempFile = lockPath.resolveSibling(lockPath.getFileName() + ".tmp");
        var jsonResult = LockSerializer.toJson(lock);
        JsonElement json = jsonResult.result().orElseThrow(() -> new IllegalStateException("Failed to serialize lock"));

        try (Writer writer = Files.newBufferedWriter(tempFile)){
            LockSerializer.GSON.toJson(json,writer);
        }

        Files.move(tempFile, lockPath, ATOMIC_MOVE, REPLACE_EXISTING);
    }
}
