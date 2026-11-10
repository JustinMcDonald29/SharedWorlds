package com.cbiv.sharedworlds.config;

import com.cbiv.sharedworlds.common.SharedWorldsSchemas;
import com.cbiv.sharedworlds.fs.WorldSourceType;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Holds this computer's sharedworlds.json and writes changes back to it.
 *
 * The coordinator reads current() on every launch, so a world added in game takes effect immediately.
 * A missing file is a normal first run (empty config). A file that exists but can't be read is never
 * overwritten: changes are refused until the player fixes or removes it, so a typo never costs them their
 * other entries.
 */
public final class SharedWorldConfigStore {

    private static final Logger LOGGER = LoggerFactory.getLogger("SharedWorldConfigStore");

    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(WorldSourceType.class, new WorldSourceTypeAdapter())
            .setPrettyPrinting()
            .create();

    private final Path file;
    private volatile GlobalSharedWorldConfig current;
    // False if the file exists but couldn't be read: writing would replace the player's entries
    private volatile boolean writable;

    private SharedWorldConfigStore(Path file, GlobalSharedWorldConfig current, boolean writable) {
        this.file = file;
        this.current = current;
        this.writable = writable;
    }

    /** Loads the config file. Never throws: problems are logged and give an empty config. */
    public static SharedWorldConfigStore load(Path file) {
        if (!Files.exists(file)) {
            LOGGER.info("No {} yet; no worlds are shared on this computer", file.getFileName());
            return new SharedWorldConfigStore(file, emptyConfig(), true);
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            GlobalSharedWorldConfig config = GSON.fromJson(reader, GlobalSharedWorldConfig.class);
            if (config == null) {
                throw new JsonParseException("file is empty");
            }
            if (config.schemaVersion() != SharedWorldsSchemas.GLOBAL_CONFIG) {
                throw new JsonParseException("unsupported schemaVersion " + config.schemaVersion());
            }
            List<GlobalSharedWorldConfig.SharedWorldEntry> worlds =
                    config.worlds() == null ? List.of() : List.copyOf(config.worlds());
            LOGGER.info("Loaded {} (schema={}, worlds={})", file, config.schemaVersion(), worlds.size());
            return new SharedWorldConfigStore(file,
                    new GlobalSharedWorldConfig(config.schemaVersion(), worlds), true);
        } catch (IOException | JsonParseException | IllegalArgumentException e) {
            LOGGER.error("Could not read {}; no worlds are treated as shared, and the file won't be changed "
                    + "until it's fixed", file, e);
            return new SharedWorldConfigStore(file, emptyConfig(), false);
        }
    }

    public GlobalSharedWorldConfig current() {
        return current;
    }

    public Path file() {
        return file;
    }

    /** False if the file exists but couldn't be read at startup; it then can't be changed until it's fixed. */
    public boolean isWritable() {
        return writable;
    }

    /** Adds an entry and saves the file. */
    public synchronized void addWorld(GlobalSharedWorldConfig.SharedWorldEntry entry) throws IOException {
        requireWritable();
        if (current.findByLocalName(entry.local().name()).isPresent()) {
            throw new IOException("World " + entry.local().name() + " is already in " + file.getFileName());
        }
        List<GlobalSharedWorldConfig.SharedWorldEntry> worlds = new ArrayList<>(current.worlds());
        worlds.add(entry);
        save(worlds);
        LOGGER.info("Added shared world {} to {}", entry.local().name(), file);
    }

    /**
     * Replaces the entry with the same local name (e.g. re-adding a world whose local copy was deleted).
     */
    public synchronized void replaceWorld(GlobalSharedWorldConfig.SharedWorldEntry entry) throws IOException {
        requireWritable();
        String name = entry.local().name();
        List<GlobalSharedWorldConfig.SharedWorldEntry> worlds = new ArrayList<>();
        boolean replaced = false;
        for (GlobalSharedWorldConfig.SharedWorldEntry existing : current.worlds()) {
            if (existing.local() != null && name.equals(existing.local().name())) {
                worlds.add(entry);
                replaced = true;
            } else {
                worlds.add(existing);
            }
        }
        if (!replaced) {
            worlds.add(entry);
        }
        save(worlds);
        LOGGER.info("Replaced shared world {} in {}", name, file);
    }

    /**
     * Removes a world's entry. Only this computer's link is forgotten: nothing is deleted anywhere.
     *
     * @return true if there was an entry to remove
     */
    public synchronized boolean removeWorld(String localName) throws IOException {
        if (current.findByLocalName(localName).isEmpty()) {
            return false;
        }
        requireWritable();
        save(current.worlds().stream()
                .filter(e -> e.local() == null || !localName.equals(e.local().name()))
                .toList());
        LOGGER.info("Removed shared world {} from {}", localName, file);
        return true;
    }

    private void requireWritable() throws IOException {
        if (!writable) {
            throw new IOException(file + " could not be read when the game started, so it wasn't changed. "
                    + "Fix or remove it and restart the game");
        }
    }

    private void save(List<GlobalSharedWorldConfig.SharedWorldEntry> worlds) throws IOException {
        GlobalSharedWorldConfig updated =
                new GlobalSharedWorldConfig(SharedWorldsSchemas.GLOBAL_CONFIG, List.copyOf(worlds));
        write(updated);
        this.current = updated;
    }

    /** Writes to a temporary file first, so a failed write never leaves a half-written config. */
    private void write(GlobalSharedWorldConfig config) throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, GSON.toJson(config), StandardCharsets.UTF_8);
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static GlobalSharedWorldConfig emptyConfig() {
        return new GlobalSharedWorldConfig(SharedWorldsSchemas.GLOBAL_CONFIG, List.of());
    }
}
