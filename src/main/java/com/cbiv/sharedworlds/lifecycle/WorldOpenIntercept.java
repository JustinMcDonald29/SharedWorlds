package com.cbiv.sharedworlds.lifecycle;

import com.cbiv.sharedworlds.SharedWorldsClient;
import com.cbiv.sharedworlds.WorldRuntimeCoordinator;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.world.WorldListWidget;
import net.minecraft.util.path.SymlinkValidationException;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.minecraft.world.level.storage.LevelStorage;
//import net.minecraft.world.level.storage.LevelStorageException;
import net.minecraft.world.level.storage.LevelSummary;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Mixin(WorldListWidget.WorldEntry.class)
public class WorldOpenIntercept {

    @Unique
    private static final Logger LOGGER = LoggerFactory.getLogger("WorldOpenIntercept");
    @Unique
    WorldRuntimeCoordinator coordinator = SharedWorldsClient.coordinator();

    @Final
    @Shadow
    LevelSummary level;

    @Final
    @Shadow
    private MinecraftClient client;

    /*
    Exact method to inject into is subject to change: Minecraft seems like it may have existing
    logic to gray out the play button on "unplayable" worlds that we could hijack instead of play()
     */

    @Inject(method = "play", at = @At("HEAD"))
    private void onPlaySelectedWorld(CallbackInfo ci) {
        LOGGER.info("World Launched");

        LevelStorage levelStorage = client.getLevelStorage();
        String levelName = level.getName();

        try (LevelStorage.Session session = levelStorage.createSession(levelName)) {

            Path savesDir = MinecraftClient.getInstance()
                    .getLevelStorage()
                    .getSavesDirectory();
            Path worldDir = savesDir.resolve(session.getDirectoryName());
            Path lockFile = worldDir.resolve("lock.json");

            LOGGER.info("Attempting to open world: {}", level.getName());
            LOGGER.info("World path: {}", worldDir);
            coordinator.beginWorldSession(worldDir);

            /*
            This is a crude implementation for testing.  The current plan is actually to check if the selected
            world has a counterpart in the shared directory, and check if that counterpart is locked.
            Regardless this will serve for testing locking and unlocking behaviour in vitro
             */
            if (Files.exists(lockFile)) {
                LOGGER.warn("LOCK FILE DETECTED for world {}", level.getName());
            }
        } catch (IOException | SymlinkValidationException e) {
            LOGGER.error("Failed to access world directory", e);
        }
    }
}
