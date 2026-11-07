package com.cbiv.sharedworlds.config;

import com.cbiv.sharedworlds.common.SharedWorldsFiles;
import com.cbiv.sharedworlds.common.SharedWorldsSchemas;
import com.cbiv.sharedworlds.fs.DirectoryWorldSource;
import com.cbiv.sharedworlds.fs.WorldSource;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Runtime registry for SharedWorldMetadata.
 * Owns reading, caching, validating and writing metadata for worlds, building WorldSources, and deciding which
 * copy of a shared world to run.
 */
public class SharedWorldMetadataRegistry {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .create();

    private static final Logger LOGGER = LoggerFactory.getLogger("SharedWorldMetadataRegistry");

    // Cached local metadata. An empty Optional means "read, and the world has no (current) metadata".
    private final Map<Path, Optional<SharedWorldMetadata>> worldRegistry = new HashMap<>();

    /**
     * Reads a local world's metadata, using the cache if it has been read before.
     *
     * @return the metadata, or empty if the world has none, or only metadata from before world IDs (schema 1)
     * @throws MetadataValidationException if the file exists but is corrupt or from a newer version of the mod
     */
    public Optional<SharedWorldMetadata> readLocal(Path worldDir) throws IOException, MetadataValidationException {
        Optional<SharedWorldMetadata> cached = worldRegistry.get(worldDir);
        if (cached != null) {
            return cached;
        }

        Path metaFile = worldDir.resolve(SharedWorldsFiles.WORLD_METADATA);
        Optional<SharedWorldMetadata> metadata;
        if (!Files.exists(metaFile)) {
            LOGGER.debug("No metadata in local world {}", worldDir);
            metadata = Optional.empty();
        } else {
            try (Reader reader = Files.newBufferedReader(metaFile, StandardCharsets.UTF_8)) {
                metadata = parse(reader, worldDir.toString());
            }
        }
        worldRegistry.put(worldDir, metadata);
        return metadata;
    }

    /**
     * Reads the source's metadata.
     *
     * @return the metadata, or empty if the source has none, or only metadata from before world IDs (schema 1)
     * @throws MetadataValidationException if the file exists but is corrupt or from a newer version of the mod
     */
    public Optional<SharedWorldMetadata> readSource(WorldSource source) throws IOException, MetadataValidationException {
        Optional<InputStream> stream = source.openMetadata();
        if (stream.isEmpty()) {
            return Optional.empty();
        }
        try (Reader reader = new InputStreamReader(stream.get(), StandardCharsets.UTF_8)) {
            return parse(reader, source.describe());
        }
    }

    /**
     * Saves metadata to a world directory and updates the cache.
     */
    public void save(Path worldDir, SharedWorldMetadata metadata) throws IOException, MetadataValidationException {
        validate(metadata, worldDir.toString());
        Path metaFile = worldDir.resolve(SharedWorldsFiles.WORLD_METADATA);
        try (Writer writer = Files.newBufferedWriter(metaFile, StandardCharsets.UTF_8)) {
            GSON.toJson(metadata, writer);
        }
        LOGGER.info("Saved metadata for world {} (worldId={}, version={}, unpushed={})",
                worldDir, metadata.worldId(), metadata.version(), metadata.unpushed());
        worldRegistry.put(worldDir, Optional.of(metadata));
    }

    /**
     * Drops the cached metadata for a world directory, e.g. after its folder was replaced by an injection.
     * The next lookup reloads it from disk.
     */
    public void forget(Path worldDir) {
        worldRegistry.remove(worldDir);
    }

    /**
     * Records that a shared world is being played on this computer: sets its unpushed flag, and gives it a world
     * ID if it doesn't have one yet (the first time it is played as a shared world).
     */
    public void markPlayed(Path worldDir) throws IOException, MetadataValidationException {
        Optional<SharedWorldMetadata> current = readLocal(worldDir);
        if (current.isPresent() && current.get().unpushed()) {
            return;
        }
        SharedWorldMetadata metadata = current.orElseGet(() -> {
            SharedWorldMetadata created = SharedWorldMetadata.createNew();
            LOGGER.info("World {} has no world ID yet; created {}", worldDir.getFileName(), created.worldId());
            return created;
        });
        save(worldDir, metadata.withUnpushed(true));
    }

    /**
     * After a pull the local copy matches the source, so it has nothing unpushed. Normally the source's metadata
     * already says so; this covers a source without metadata, where the injector kept the local file.
     */
    public void markPulled(Path worldDir) throws IOException, MetadataValidationException {
        Optional<SharedWorldMetadata> current = readLocal(worldDir);
        if (current.isPresent() && current.get().unpushed()) {
            save(worldDir, current.get().withUnpushed(false));
        }
    }

    /**
     * Step 1 of a push: works out the next version and records locally that this push is in flight.
     *
     * A push must run these steps in this order:
     *  1. beginPush (before copying anything)
     *  2. copy the world's files to the source
     *  3. write the returned metadata to the source LAST, so the source's version only goes up once its files
     *     are complete. Never copy the local sharedworld.meta.json instead: it has the old version and the
     *     local-only pendingPushId
     *  4. completePush
     * Stopping before step 3 leaves the source unchanged and the local copy unpushed (LOCAL_AHEAD next launch, so
     * it's pushed again). Stopping between 3 and 4 is recognised next launch by the pending push ID
     * (OWN_PUSH_LANDED) instead of being mistaken for DIVERGED.
     *
     * @param pushedBy player name, recorded as lastPushedBy
     * @return the metadata to write to the source in step 3, and to pass to completePush in step 4
     */
    public SharedWorldMetadata beginPush(Path worldDir, String pushedBy)
            throws IOException, MetadataValidationException {
        SharedWorldMetadata current = readLocal(worldDir).orElseThrow(() ->
                new CorruptMetadataException("World " + worldDir + " has no metadata to push"));
        SharedWorldMetadata next = current.nextPush(pushedBy, Instant.now());
        save(worldDir, current.withPendingPush(next.lastPushId()));
        LOGGER.info("Starting push {} of {}: version {} -> {}",
                next.lastPushId(), worldDir.getFileName(), current.version(), next.version());
        return next;
    }

    /**
     * Step 4 of a push: the source has the new version, so the local copy now matches it.
     *
     * @param pushed the metadata returned by beginPush (and written to the source)
     */
    public void completePush(Path worldDir, SharedWorldMetadata pushed)
            throws IOException, MetadataValidationException {
        save(worldDir, pushed);
        LOGGER.info("Push {} of {} complete: now at version {}",
                pushed.lastPushId(), worldDir.getFileName(), pushed.version());
    }

    /**
     * Builds the WorldSource for a world's source, as declared in its global config entry.
     * This is the only place WorldSources are constructed.
     *
     * @return the source, or empty if the entry is incomplete or its type isn't supported yet
     */
    public Optional<WorldSource> getSource(GlobalSharedWorldConfig.Source source) {
        if (source == null || source.type() == null
                || source.location() == null || source.location().isBlank()) {
            LOGGER.warn("Shared world source is missing its type or location: {}", source);
            return Optional.empty();
        }

        return switch (source.type()) {
            case DIRECTORY -> {
                try {
                    yield Optional.of(new DirectoryWorldSource(Path.of(source.location())));
                } catch (InvalidPathException e) {
                    LOGGER.warn("Invalid directory source location: {}", source.location(), e);
                    yield Optional.empty();
                }
            }
            default -> {
                LOGGER.warn("Source type {} is not supported yet", source.type().id());
                yield Optional.empty();
            }
        };
    }

    /**
     * Compares the local copy of a shared world with its source and decides which to run.
     * See SharedWorldComparator for the rules.
     */
    public WorldDecision decide(Path localWorldDir, WorldSource source)
            throws IOException, MetadataValidationException {
        LOGGER.info("Comparing local world {} against source {}", localWorldDir, source.describe());

        Optional<SharedWorldMetadata> local = readLocal(localWorldDir);
        Optional<SharedWorldMetadata> remote = readSource(source);

        LevelInfo localLevel = readLocalLevel(localWorldDir);
        LevelInfo sourceLevel;
        try {
            sourceLevel = LevelDatReader.read(source.openLevelDat(), source.describe());
        } catch (IOException e) {
            LOGGER.error("Could not open level.dat of {}", source.describe(), e);
            sourceLevel = LevelInfo.unknown();
        }

        WorldDecision decision = SharedWorldComparator.decide(local, remote, localLevel, sourceLevel);
        if (decision == WorldDecision.OWN_PUSH_LANDED) {
            completePush(localWorldDir, remote.orElseThrow());
            decision = WorldDecision.UP_TO_DATE;
        }
        LOGGER.info("World decision: local={}, source={}, decision={}",
                localWorldDir.getFileName(), source.describe(), decision);
        return decision;
    }

    private static LevelInfo readLocalLevel(Path worldDir) {
        Path levelDat = worldDir.resolve("level.dat");
        try {
            return LevelDatReader.read(Files.newInputStream(levelDat), worldDir.toString());
        } catch (IOException e) {
            LOGGER.error("Could not open level.dat of {}", worldDir, e);
            return LevelInfo.unknown();
        }
    }

    /**
     * Parses a metadata file. Schema 1 files (before world IDs) carry no identity, so they read as empty.
     */
    private static Optional<SharedWorldMetadata> parse(Reader reader, String description)
            throws MetadataValidationException {
        JsonObject json;
        try {
            JsonElement element = JsonParser.parseReader(reader);
            if (!element.isJsonObject()) {
                throw new CorruptMetadataException("Metadata of " + description + " is not a JSON object");
            }
            json = element.getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            throw new CorruptMetadataException("Metadata of " + description + " is not valid JSON", e);
        }

        int schema;
        try {
            schema = json.has("schemaVersion") ? json.get("schemaVersion").getAsInt() : -1;
        } catch (RuntimeException e) {
            throw new CorruptMetadataException("Metadata of " + description + " has an invalid schemaVersion", e);
        }
        if (schema == 1) {
            LOGGER.info("Metadata of {} is from before world IDs (schema 1); treating the world as having no ID",
                    description);
            return Optional.empty();
        }

        SharedWorldMetadata metadata;
        try {
            metadata = GSON.fromJson(json, SharedWorldMetadata.class);
        } catch (JsonParseException e) {
            throw new CorruptMetadataException("Metadata of " + description + " could not be read", e);
        }
        validate(metadata, description);
        return Optional.of(metadata);
    }

    /**
     * Validates the schema of a metadata object.
     */
    private static void validate(SharedWorldMetadata metadata, String description) throws MetadataValidationException {
        if (metadata.schemaVersion() != SharedWorldsSchemas.WORLD_METADATA) {
            LOGGER.error("Metadata schema mismatch for {} (expected={}, actual={})",
                    description, SharedWorldsSchemas.WORLD_METADATA, metadata.schemaVersion());
            throw new SchemaMismatchException("Metadata schema version mismatch for " + description
                    + ": expected " + SharedWorldsSchemas.WORLD_METADATA + " but got " + metadata.schemaVersion());
        }
        if (metadata.worldId() == null) {
            throw new CorruptMetadataException("Metadata of " + description + " has no worldId");
        }
        if (metadata.version() < 0) {
            throw new CorruptMetadataException("Metadata of " + description + " has a negative version");
        }
    }
}
