package com.cbiv.sharedworlds.service;

import com.cbiv.sharedworlds.common.SharedWorldsFiles;
import com.cbiv.sharedworlds.config.GlobalSharedWorldConfig;
import com.cbiv.sharedworlds.config.MetadataValidationException;
import com.cbiv.sharedworlds.config.SharedWorldConfigStore;
import com.cbiv.sharedworlds.config.SharedWorldMetadata;
import com.cbiv.sharedworlds.config.SharedWorldMetadataRegistry;
import com.cbiv.sharedworlds.fs.WorldBlueprint;
import com.cbiv.sharedworlds.fs.WorldInjector;
import com.cbiv.sharedworlds.fs.WorldSource;
import com.cbiv.sharedworlds.fs.WorldSourceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Every action that sets up or changes a shared world goes through here. Screens only call this class, so they
 * stay thin, and a command or companion app could call the same methods later.
 *
 * Each action comes in two steps: plan...() checks everything and works out exactly what will happen (for the
 * player to confirm), then the action itself re-checks and carries it out. Plain file code, no Minecraft
 * classes: actions may run on a background thread, but never while a world is open.
 */
public final class SharedWorldsService {

    private static final Logger LOGGER = LoggerFactory.getLogger("SharedWorldsService");

    private final Path savesDir;
    private final SharedWorldConfigStore configStore;
    private final SharedWorldMetadataRegistry registry;
    private final WorldInjector injector;
    private final String playerName;

    public SharedWorldsService(Path savesDir, SharedWorldConfigStore configStore,
                               SharedWorldMetadataRegistry registry, WorldInjector injector, String playerName) {
        this.savesDir = savesDir.toAbsolutePath().normalize();
        this.configStore = configStore;
        this.registry = registry;
        this.injector = injector;
        this.playerName = playerName;
    }

    /** The config entry for a world in saves/, if it's shared. */
    public Optional<GlobalSharedWorldConfig.SharedWorldEntry> findShared(String localName) {
        return configStore.current().findByLocalName(localName);
    }

    /* ------------------------------------------------------------
       Share an existing local world
       ------------------------------------------------------------ */

    /**
     * @param target          the folder the shared copy will be pushed to
     * @param useChosenFolder true if the player picked an empty folder, which becomes the shared copy itself;
     *                        false if a new folder named after the world is created inside the one they picked
     */
    public record SharePlan(String localName, Path target, boolean useChosenFolder) {}

    /**
     * Works out where a local world's shared copy would go.
     *
     * @param chosenFolder the folder the player picked. If it's empty it becomes the shared copy; otherwise a
     *                     folder named after the world is created inside it
     */
    public SharePlan planShare(String localName, Path chosenFolder) throws SharedWorldsException {
        requireWritableConfig();
        Path localDir = localWorld(localName);
        if (findShared(localName).isPresent()) {
            throw new SharedWorldsException("\"" + localName + "\" is already shared.");
        }
        if (!Files.isRegularFile(localDir.resolve("level.dat"))) {
            throw new SharedWorldsException("\"" + localName + "\" doesn't look like a complete world "
                    + "(it has no level.dat).");
        }
        checkNotACopyOfAnotherSharedWorld(localName, localDir);

        Path chosen = existingFolder(chosenFolder);
        if (isSameOrInside(chosen, savesDir)) {
            throw new SharedWorldsException("Pick a folder outside your saves folder: the shared copy has to "
                    + "live somewhere the other players can reach.");
        }
        if (Files.exists(chosen.resolve("level.dat"))) {
            throw new SharedWorldsException("That folder is already a Minecraft world. Pick the folder you "
                    + "want the shared copy to go in.");
        }

        if (isEmptyFolder(chosen)) {
            return new SharePlan(localName, chosen, true);
        }
        Path target = chosen.resolve(localName);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new SharedWorldsException("That folder already has something called \"" + localName
                    + "\" in it. Pick another folder, or an empty one.");
        }
        return new SharePlan(localName, target, false);
    }

    /**
     * Shares a local world: pushes it to the planned folder (as version 1) and adds it to sharedworlds.json.
     * If the push fails, everything this call put in the target folder is removed again.
     */
    public void share(SharePlan plan) throws SharedWorldsException {
        requireWritableConfig();
        String localName = plan.localName();
        Path localDir = localWorld(localName);
        Path target = plan.target();

        // Things may have changed while the player was reading the confirmation
        if (findShared(localName).isPresent()) {
            throw new SharedWorldsException("\"" + localName + "\" is already shared.");
        }
        if (plan.useChosenFolder() ? !isEmptyFolder(target) : Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new SharedWorldsException("The folder " + target + " changed since you picked it. "
                    + "Try again.");
        }

        // The world keeps its ID if it has one (it was shared before and unlinked); otherwise it gets one now
        try {
            if (registry.readLocal(localDir).isEmpty()) {
                SharedWorldMetadata created = SharedWorldMetadata.createNew();
                registry.save(localDir, created);
                LOGGER.info("Created world ID {} for {}", created.worldId(), localName);
            }
        } catch (IOException | MetadataValidationException e) {
            throw new SharedWorldsException("Couldn't set up the shared-world file for \"" + localName + "\": "
                    + e.getMessage(), e);
        }

        WorldSource source = directorySource(target);
        boolean createdTarget = false;
        SharedWorldMetadata next;
        try {
            if (!plan.useChosenFolder()) {
                Files.createDirectory(target);
                createdTarget = true;
            }
            // Same order as every push (see SharedWorldMetadataRegistry.beginPush): the metadata goes last, so a
            // folder without it is never mistaken for a finished shared world
            next = registry.beginPush(localDir, playerName);
            source.pushFiles(localDir, WorldBlueprint.WORLD_ENTRIES);
            source.writeMetadata(registry.serialize(next));
        } catch (IOException | MetadataValidationException | RuntimeException e) {
            LOGGER.error("Sharing {} to {} failed", localName, target, e);
            cleanUpFailedShare(target, createdTarget);
            throw new SharedWorldsException("Couldn't copy \"" + localName + "\" to " + target + ": "
                    + e.getMessage(), e);
        }

        try {
            registry.completePush(localDir, next);
        } catch (IOException | MetadataValidationException e) {
            // The shared copy is complete; the next launch recognises this push by its ID (OWN_PUSH_LANDED)
            LOGGER.error("Shared {} but couldn't record it locally; it will be recognised on the next launch",
                    localName, e);
        }

        try {
            configStore.addWorld(directoryEntry(localName, target));
        } catch (IOException e) {
            throw new SharedWorldsException("\"" + localName + "\" was copied to " + target + ", but "
                    + "sharedworlds.json couldn't be updated (" + e.getMessage() + "), so it isn't linked yet.",
                    e);
        }
        LOGGER.info("Shared {} to {}", localName, target);
    }

    /* ------------------------------------------------------------
       Add a world someone else shared
       ------------------------------------------------------------ */

    /**
     * @param sourceDir    the shared copy's folder
     * @param localName    the folder name in saves/ it will be (or already is) in
     * @param worldId      the shared world's ID
     * @param linkExisting true if a local copy of this world already exists and is only linked, not copied
     * @param replacesStaleEntry true if this computer's config still has an entry for this shared copy whose
     *                     local world was deleted; the world is copied back in under that entry's name
     */
    public record AddPlan(Path sourceDir, String localName, UUID worldId, long version, boolean linkExisting,
                          boolean replacesStaleEntry) {}

    /**
     * Works out how a shared world would be added.
     *
     * @param chosenFolder the shared world's folder, or a folder containing exactly one world
     */
    public AddPlan planAdd(Path chosenFolder) throws SharedWorldsException {
        requireWritableConfig();
        Path sourceDir = findWorldIn(existingFolder(chosenFolder));
        if (isSameOrInside(sourceDir, savesDir)) {
            throw new SharedWorldsException("That's one of your own worlds. To share it, select it and use "
                    + "Share instead.");
        }

        SharedWorldMetadata sourceMeta = readSharedMetadata(sourceDir);

        for (GlobalSharedWorldConfig.SharedWorldEntry entry : configStore.current().worlds()) {
            if (entry.source() != null && entry.source().type() == WorldSourceType.DIRECTORY
                    && entry.source().location() != null && samePath(entry.source().location(), sourceDir)) {
                String name = entry.local().name();
                if (Files.exists(localWorld(name), LinkOption.NOFOLLOW_LINKS)) {
                    throw new SharedWorldsException("This shared world is already in your worlds as \""
                            + name + "\".");
                }
                // Linked once, but its local copy was deleted outside the game: copy it back under that name
                return new AddPlan(sourceDir, name, sourceMeta.worldId(), sourceMeta.version(), false, true);
            }
        }

        Optional<String> existing = findLocalWorldWithId(sourceMeta.worldId());
        if (existing.isPresent()) {
            if (findShared(existing.get()).isPresent()) {
                throw new SharedWorldsException("You already have this world as \"" + existing.get()
                        + "\", linked to a different folder.");
            }
            return new AddPlan(sourceDir, existing.get(), sourceMeta.worldId(), sourceMeta.version(), true, false);
        }

        String localName = uniqueLocalName(sourceDir.getFileName().toString());
        return new AddPlan(sourceDir, localName, sourceMeta.worldId(), sourceMeta.version(), false, false);
    }

    /**
     * Adds a shared world: copies it into saves/ (or links the existing local copy) and adds it to
     * sharedworlds.json. Doesn't need the lock: nothing is written to the shared copy.
     */
    public void add(AddPlan plan) throws SharedWorldsException {
        requireWritableConfig();
        String localName = plan.localName();
        Path localDir = localWorld(localName);
        if (!plan.replacesStaleEntry() && findShared(localName).isPresent()) {
            throw new SharedWorldsException("\"" + localName + "\" is already shared.");
        }

        if (plan.linkExisting()) {
            if (!Files.isDirectory(localDir)) {
                throw new SharedWorldsException("\"" + localName + "\" is no longer in your saves folder.");
            }
            // Its version is compared with the shared copy's on the next launch, as for any shared world
            addToConfig(localName, plan.sourceDir());
            LOGGER.info("Linked existing local world {} to {}", localName, plan.sourceDir());
            return;
        }

        if (Files.exists(localDir, LinkOption.NOFOLLOW_LINKS)) {
            throw new SharedWorldsException("A world called \"" + localName + "\" appeared in your saves folder "
                    + "while you were choosing. Try again.");
        }

        // Read the metadata BEFORE copying. If someone pushes while the copy runs, this copy ends up with
        // some newer files but the older version number, so the next launch pulls the finished push. Keeping
        // the metadata copied afterwards would claim the new version and never fix the mix.
        SharedWorldMetadata before = readSharedMetadata(plan.sourceDir());
        if (!before.worldId().equals(plan.worldId())) {
            throw new SharedWorldsException("The world in " + plan.sourceDir() + " changed since you picked it. "
                    + "Try again.");
        }

        try {
            injector.inject(plan.sourceDir(), localDir);
        } catch (IOException | RuntimeException e) {
            LOGGER.error("Copying {} into saves failed", plan.sourceDir(), e);
            throw new SharedWorldsException("Couldn't copy the world from " + plan.sourceDir() + ": "
                    + e.getMessage(), e);
        } finally {
            registry.forget(localDir);
        }

        try {
            registry.save(localDir, before);
        } catch (IOException | MetadataValidationException e) {
            throw new SharedWorldsException("The world was copied, but its shared-world file couldn't be "
                    + "written: " + e.getMessage(), e);
        }

        if (plan.replacesStaleEntry()) {
            try {
                configStore.replaceWorld(directoryEntry(localName, plan.sourceDir()));
            } catch (IOException e) {
                throw new SharedWorldsException("\"" + localName + "\" is in your worlds, but sharedworlds.json "
                        + "couldn't be updated (" + e.getMessage() + ").", e);
            }
        } else {
            addToConfig(localName, plan.sourceDir());
        }
        LOGGER.info("Added shared world {} from {} (version {})", localName, plan.sourceDir(), before.version());
    }

    /* ------------------------------------------------------------
       Local world deleted
       ------------------------------------------------------------ */

    /**
     * Called after Minecraft's Delete. If the world was shared and its folder is really gone, this computer's
     * link to it is removed. The shared copy is never touched: deleting it stays a deliberate, manual act.
     *
     * @return true if a config entry was removed
     */
    public boolean onLocalWorldDeleted(String localName) {
        Path localDir = localWorld(localName);
        registry.forget(localDir);
        if (findShared(localName).isEmpty()) {
            return false;
        }
        if (Files.exists(localDir, LinkOption.NOFOLLOW_LINKS)) {
            LOGGER.warn("Shared world {} was deleted but its folder is still there; keeping its link", localName);
            return false;
        }
        try {
            return configStore.removeWorld(localName);
        } catch (IOException e) {
            LOGGER.error("Deleted {}, but couldn't remove it from {}", localName, configStore.file(), e);
            return false;
        }
    }

    /* ------------------------------------------------------------
       Internals
       ------------------------------------------------------------ */

    /** Checked first, so nothing is copied for a change that couldn't be saved. */
    private void requireWritableConfig() throws SharedWorldsException {
        if (!configStore.isWritable()) {
            throw new SharedWorldsException(configStore.file() + " couldn't be read when the game started, so "
                    + "SharedWorlds won't change it. Fix or remove it and restart the game.");
        }
    }

    private void addToConfig(String localName, Path sourceDir) throws SharedWorldsException {
        try {
            configStore.addWorld(directoryEntry(localName, sourceDir));
        } catch (IOException e) {
            throw new SharedWorldsException("\"" + localName + "\" is in your worlds, but sharedworlds.json "
                    + "couldn't be updated (" + e.getMessage() + "). Use Add again to link it.", e);
        }
    }

    private static GlobalSharedWorldConfig.SharedWorldEntry directoryEntry(String localName, Path sourceDir) {
        return new GlobalSharedWorldConfig.SharedWorldEntry(
                localName,
                new GlobalSharedWorldConfig.Local(localName),
                new GlobalSharedWorldConfig.Source(WorldSourceType.DIRECTORY, sourceDir.toString()),
                GlobalSharedWorldConfig.Behaviour.defaults());
    }

    private WorldSource directorySource(Path dir) throws SharedWorldsException {
        return registry.getSource(new GlobalSharedWorldConfig.Source(WorldSourceType.DIRECTORY, dir.toString()))
                .orElseThrow(() -> new SharedWorldsException("Can't use " + dir + " as a shared folder."));
    }

    private SharedWorldMetadata readSharedMetadata(Path sourceDir) throws SharedWorldsException {
        try {
            return registry.readSource(directorySource(sourceDir)).orElseThrow(() -> new SharedWorldsException(
                    "This world hasn't been shared yet (it has no " + SharedWorldsFiles.WORLD_METADATA + "). "
                            + "To share it, copy it into your saves folder, select it and use Share."));
        } catch (IOException | MetadataValidationException e) {
            throw new SharedWorldsException("Couldn't read the shared world's details: " + e.getMessage(), e);
        }
    }

    /** A shared world must have its own ID: a world copied from a shared one would push over the original. */
    private void checkNotACopyOfAnotherSharedWorld(String localName, Path localDir) throws SharedWorldsException {
        Optional<SharedWorldMetadata> meta;
        try {
            meta = registry.readLocal(localDir);
        } catch (IOException | MetadataValidationException e) {
            throw new SharedWorldsException("The shared-world file in \"" + localName + "\" can't be read: "
                    + e.getMessage(), e);
        } finally {
            registry.forget(localDir);
        }
        if (meta.isEmpty()) {
            return;
        }
        for (GlobalSharedWorldConfig.SharedWorldEntry entry : configStore.current().worlds()) {
            String other = entry.local() == null ? null : entry.local().name();
            if (other == null || other.equals(localName)) {
                continue;
            }
            if (worldIdOf(localWorld(other)).filter(meta.get().worldId()::equals).isPresent()) {
                throw new SharedWorldsException("\"" + localName + "\" is a copy of your shared world \"" + other
                        + "\". Sharing copies separately isn't supported yet.");
            }
        }
    }

    /** The folder name in saves/ of a local world with this ID, if there is one. */
    private Optional<String> findLocalWorldWithId(UUID worldId) throws SharedWorldsException {
        if (!Files.isDirectory(savesDir)) {
            return Optional.empty();
        }
        try (DirectoryStream<Path> worlds = Files.newDirectoryStream(savesDir, Files::isDirectory)) {
            for (Path world : worlds) {
                if (worldIdOf(world).filter(worldId::equals).isPresent()) {
                    return Optional.of(world.getFileName().toString());
                }
            }
        } catch (IOException e) {
            throw new SharedWorldsException("Couldn't look through your saves folder: " + e.getMessage(), e);
        }
        return Optional.empty();
    }

    /** Reads a world's ID without leaving it in the registry's cache (these scans read many worlds). */
    private Optional<UUID> worldIdOf(Path worldDir) {
        if (!Files.exists(worldDir.resolve(SharedWorldsFiles.WORLD_METADATA))) {
            return Optional.empty();
        }
        try {
            return registry.readLocal(worldDir).map(SharedWorldMetadata::worldId);
        } catch (IOException | MetadataValidationException e) {
            LOGGER.warn("Skipping {}: its {} can't be read", worldDir, SharedWorldsFiles.WORLD_METADATA, e);
            return Optional.empty();
        } finally {
            registry.forget(worldDir);
        }
    }

    /** The world folder itself, or the only world folder directly inside it. */
    private static Path findWorldIn(Path folder) throws SharedWorldsException {
        if (Files.isRegularFile(folder.resolve("level.dat"))) {
            return folder;
        }
        List<Path> worlds = new ArrayList<>();
        try (DirectoryStream<Path> children = Files.newDirectoryStream(folder, Files::isDirectory)) {
            for (Path child : children) {
                if (Files.isRegularFile(child.resolve("level.dat"))) {
                    worlds.add(child);
                }
            }
        } catch (IOException e) {
            throw new SharedWorldsException("Couldn't look inside " + folder + ": " + e.getMessage(), e);
        }
        if (worlds.size() == 1) {
            return worlds.getFirst();
        }
        if (worlds.isEmpty()) {
            throw new SharedWorldsException("There's no Minecraft world in " + folder + ". Pick the shared "
                    + "world's folder (the one with level.dat in it).");
        }
        throw new SharedWorldsException(folder + " has " + worlds.size() + " worlds in it. Pick the one you "
                + "want to add.");
    }

    /** The world's folder name, or "Name (2)", "Name (3)"... if it's taken in saves/ or the config. */
    private String uniqueLocalName(String base) {
        String name = base;
        for (int n = 2; Files.exists(localWorld(name), LinkOption.NOFOLLOW_LINKS) || findShared(name).isPresent(); n++) {
            name = base + " (" + n + ")";
        }
        return name;
    }

    private Path localWorld(String localName) {
        return savesDir.resolve(localName);
    }

    private static Path existingFolder(Path folder) throws SharedWorldsException {
        Path normalized = folder.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized)) {
            throw new SharedWorldsException("Can't find the folder " + normalized + ".");
        }
        return normalized;
    }

    private static boolean isEmptyFolder(Path folder) {
        if (!Files.isDirectory(folder)) {
            return false;
        }
        try (Stream<Path> children = Files.list(folder)) {
            return children.findAny().isEmpty();
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean samePath(String location, Path path) {
        try {
            Path other = Path.of(location).toAbsolutePath().normalize();
            if (other.equals(path)) {
                return true;
            }
            return Files.exists(other) && Files.isSameFile(other, path);
        } catch (RuntimeException | IOException e) {
            return false;
        }
    }

    private static boolean isSameOrInside(Path path, Path folder) {
        if (path.startsWith(folder)) {
            return true;
        }
        try {
            return path.toRealPath().startsWith(folder.toRealPath());
        } catch (IOException e) {
            return false;
        }
    }

    /** Removes what a failed share wrote: the folder it created, or the contents of the empty one picked. */
    private static void cleanUpFailedShare(Path target, boolean createdTarget) {
        try {
            if (createdTarget) {
                deleteRecursively(target);
            } else if (Files.isDirectory(target)) {
                try (DirectoryStream<Path> children = Files.newDirectoryStream(target)) {
                    for (Path child : children) {
                        deleteRecursively(child);
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.error("Couldn't clean up {} after a failed share; delete it by hand", target, e);
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) throw exc;
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
