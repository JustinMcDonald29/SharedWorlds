package com.cbiv.sharedworlds.config;

import com.cbiv.sharedworlds.common.SharedWorldsFiles;
import com.cbiv.sharedworlds.common.SharedWorldsSchemas;
import com.cbiv.sharedworlds.fs.DirectoryWorldSource;
import com.cbiv.sharedworlds.fs.WorldSource;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Unique;


/**
 * Runtime registry for SharedWorldMetadata.
 * Owns reading, caching, validating, and writing metadata for worlds.
 * Also provides comparison of local vs source worlds.
 */
public class SharedWorldMetadataRegistry {

    private static final Gson GSON = new GsonBuilder()
            .registerTypeHierarchyAdapter(Path.class, new PathTypeAdapter())
            .setPrettyPrinting()
            .create();

    private final Map<Path, SharedWorldMetadata> worldRegistry = new HashMap<>();
    @Unique
    private static final Logger LOGGER = LoggerFactory.getLogger("SharedWorldMetadataRegistry");

    /**
     * Loads metadata from a world directory and caches it.
     */
    public SharedWorldMetadata load(Path worldDir) throws IOException,MetadataValidationException {
        Path metaFile = worldDir.resolve(SharedWorldsFiles.WORLD_METADATA);
        LOGGER.debug("Loading metadata for world:{}", worldDir);

        if (!Files.exists(metaFile)) {
            LOGGER.warn("Metadata file missing for world:{}", worldDir);
            throw new IOException("Metadata file does not exist: " + metaFile);

        }

        try (Reader reader = Files.newBufferedReader(metaFile)) {
            SharedWorldMetadata metadata = GSON.fromJson(reader, SharedWorldMetadata.class);
            LOGGER.info("Loaded metadata for world {} (sourceId={}, sourceType={})",
                    worldDir,
                    metadata.association().sourceId(),
                    metadata.association().sourceType());
            validate(metadata);
            worldRegistry.put(worldDir, metadata);
            return metadata;
        }
    }

    /**
     * Saves metadata to a world directory and updates the cache.
     */
    public void save(Path worldDir, SharedWorldMetadata metadata) throws IOException, MetadataValidationException {
        validate(metadata);
        LOGGER.debug("Saving metadata for world: {}", worldDir);

        Path metaFile = worldDir.resolve(SharedWorldsFiles.WORLD_METADATA);
        try (Writer writer = Files.newBufferedWriter(metaFile)) {
            GSON.toJson(metadata, writer);
        }
        LOGGER.info(
                "Updated metadata for world {} (lastPulled={}, lastPushed={})",
                worldDir,
                metadata.state().lastPulled(),
                metadata.state().lastPushed()
        );

        worldRegistry.put(worldDir, metadata);
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
     * Drops the cached metadata for a world directory, e.g. after its folder was replaced by an injection.
     * The next lookup reloads it from disk.
     */
    public void forget(Path worldDir) {
        worldRegistry.remove(worldDir);
    }

    /**
     * Returns cached metadata for a given world directory, or null if not loaded.
     */
    public SharedWorldMetadata get(Path worldDir) {
        return worldRegistry.get(worldDir);
    }

    /**
     * Compares the local world with its source to determine if the source is newer.
     * Handles missing source metadata gracefully by falling back to level.dat LastPlayed.
     *
     * @param localWorldDir Path to local world directory
     * @param source        The world's source
     * @return true if the source world is newer and should be injected
     */
    public boolean isSourceNewer(Path localWorldDir, WorldSource source) throws MetadataValidationException {
        SharedWorldMetadata localMetadata = worldRegistry.get(localWorldDir);
        SharedWorldMetadata sourceMetadata = null;
        LOGGER.info("Comparing local world {} against source {}",
                localWorldDir,
                source.describe());

        try {
            Optional<InputStream> metadataStream = source.openMetadata();
            if (metadataStream.isPresent()) {
                try (Reader reader = new InputStreamReader(metadataStream.get(), StandardCharsets.UTF_8)) {
                    sourceMetadata = GSON.fromJson(reader, SharedWorldMetadata.class);
                }
            }
        } catch (IOException | JsonParseException e) {
            // If source metadata can't be read, fall back to level.dat comparison
            LOGGER.warn("Could not read source metadata for {}, falling back to level.dat comparison",
                    source.describe(), e);
            sourceMetadata = null;
        }

        if (localMetadata == null) {
            try {
                localMetadata = load(localWorldDir);
            } catch (IOException e) {
                // Local metadata missing, assume local is "newer" to avoid accidental overwrite
                LOGGER.error(
                        "Local metadata missing for {}, refusing to overwrite local world",
                        localWorldDir
                );
                return false;
            }
        }

        boolean result = SharedWorldComparator.isSourceNewer(localWorldDir, localMetadata, source, sourceMetadata);
        LOGGER.info("World decision: local={}, source={}, inject={}",
                localWorldDir.getFileName(),
                source.describe(),
                result);
        return result;
    }

    /**
     * Validates the schema of a metadata object.
     */
    private void validate(SharedWorldMetadata metadata) throws MetadataValidationException {
        if (metadata.schemaVersion() != SharedWorldsSchemas.WORLD_METADATA) {
            LOGGER.error(
                    "Metadata schema mismatch (expected={}, actual={})",
                    SharedWorldsSchemas.WORLD_METADATA,
                    metadata.schemaVersion()
            );

            throw new SchemaMismatchException(
                    "Metadata schema version mismatch: expected " +
                            SharedWorldsSchemas.WORLD_METADATA +
                            " but got " + metadata.schemaVersion()
            );
        }
        if (metadata.association()==null){
            LOGGER.error("Missing association block");
            throw new CorruptMetadataException("Missing association block");
        }
    }
}
