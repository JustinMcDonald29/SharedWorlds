package com.cbiv.sharedworlds.fs;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.util.Uuids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.UUID;

/**
 * Makes whoever hosts a shared world load their OWN player data.
 *
 * In vanilla (checked against 1.21.11 PlayerManager.loadPlayerData), the host loads the player stored in
 * level.dat (Data.Player) if there is one, and only otherwise their own playerdata/&lt;uuid&gt;.dat. Every save
 * rewrites Data.Player with the current host, so without this the next host would take on the previous host's
 * inventory and position.
 *
 * The fix is to remove Data.Player before the world opens. The server writes every player, the host included,
 * to playerdata/ on save, so the previous host's data is already there. If it somehow isn't, it's written there
 * first, so nobody's data is lost. A first-time host spawns fresh at world spawn.
 *
 * Plain file code, run before Minecraft opens the world (no session or server yet).
 */
public final class PlayerDataRewriter {

    private static final Logger LOGGER = LoggerFactory.getLogger("PlayerDataRewriter");
    private static final String TEMP_SUFFIX = ".sharedworlds-tmp";

    private PlayerDataRewriter() {}

    /**
     * Removes the stored host player from level.dat, and from level.dat_old (Minecraft's fallback if level.dat
     * can't be read).
     *
     * @return true if level.dat was changed
     * @throws IOException if level.dat couldn't be read or written; it is then left as it was
     */
    public static boolean detachHostPlayer(Path worldDir) throws IOException {
        boolean changed = detach(worldDir.resolve("level.dat"), worldDir);
        Path old = worldDir.resolve("level.dat_old");
        if (Files.isRegularFile(old)) {
            detach(old, worldDir);
        }
        return changed;
    }

    private static boolean detach(Path levelDat, Path worldDir) throws IOException {
        NbtCompound root = NbtIo.readCompressed(levelDat, NbtSizeTracker.forLevel());
        NbtCompound data = root.getCompound("Data")
                .orElseThrow(() -> new IOException(levelDat + " has no Data compound"));
        Optional<NbtCompound> player = data.getCompound("Player");
        if (player.isEmpty()) {
            return false;
        }

        preservePlayer(player.get(), data, worldDir);
        data.remove("Player");
        writeAtomically(root, levelDat);
        LOGGER.info("Removed the previous host's player from {}", levelDat);
        return true;
    }

    /**
     * Makes sure the player about to be removed from level.dat also exists in playerdata/.
     */
    private static void preservePlayer(NbtCompound player, NbtCompound data, Path worldDir) throws IOException {
        int[] uuidInts = player.getIntArray("UUID")
                .filter(ints -> ints.length == 4)
                .orElseThrow(() -> new IOException("The player in level.dat has no UUID, so it can't be kept; "
                        + "leaving level.dat unchanged"));
        UUID uuid = Uuids.toUuid(uuidInts);

        Path playerFile = worldDir.resolve("playerdata").resolve(uuid + ".dat");
        if (Files.exists(playerFile)) {
            // Written in the same save as level.dat, so it's just as recent
            return;
        }

        NbtCompound copy = player.copy();
        if (!copy.contains("DataVersion")) {
            // Without it, Minecraft would run the player through every data upgrade since the oldest version
            data.getInt("DataVersion").ifPresent(version -> copy.putInt("DataVersion", version));
        }
        Files.createDirectories(playerFile.getParent());
        writeAtomically(copy, playerFile);
        LOGGER.info("Player {} was only stored in level.dat; saved them to {} before removing it", uuid, playerFile);
    }

    private static void writeAtomically(NbtCompound nbt, Path target) throws IOException {
        Path temp = target.resolveSibling(target.getFileName() + TEMP_SUFFIX);
        try {
            NbtIo.writeCompressed(nbt, temp);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
    }
}
