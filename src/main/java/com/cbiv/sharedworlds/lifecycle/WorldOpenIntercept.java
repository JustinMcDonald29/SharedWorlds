package com.cbiv.sharedworlds.lifecycle;

import com.cbiv.sharedworlds.SharedWorldsClient;
import com.cbiv.sharedworlds.ui.LockedOutScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.server.integrated.IntegratedServerLoader;
import net.minecraft.world.level.storage.LevelStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.file.Path;

/*
Mixin hook to detect, and gate, launches of existing worlds.

Hooks IntegratedServerLoader.start(String, Runnable), which every launch of an existing world goes through:
the world list's "Play Selected World" button (WorldListWidget.WorldEntry.play()) and Quick Play
(--quickPlaySingleplayer). Creating a new world uses a different method (createAndStart), so it never
reaches this hook.

The hook runs before Minecraft opens a LevelStorage.Session, so the world folder is untouched and
session.lock is not held yet: this is the point where a shared world can be swapped in.

WorldRuntimeCoordinator.beginWorldSession decides whether the launch may continue:
  true  -> continue launching as normal (not shared, local copy is current, or injection succeeded)
  false -> cancel the launch and return the player to the previous screen via onCancel
 */
@Mixin(IntegratedServerLoader.class)
public class WorldOpenIntercept {

    @Unique
    private static final Logger LOGGER = LoggerFactory.getLogger("WorldOpenIntercept");

    @Shadow
    @Final
    private LevelStorage storage;

    @Inject(method = "start(Ljava/lang/String;Ljava/lang/Runnable;)V", at = @At("HEAD"), cancellable = true)
    private void onStartExistingWorld(String levelName, Runnable onCancel, CallbackInfo ci) {
        Path worldDir = this.storage.getSavesDirectory().resolve(levelName);
        LOGGER.info("Attempting to open world: {}", levelName);
        LOGGER.info("World path: {}", worldDir);

        boolean allowLaunch;
        try {
            allowLaunch = SharedWorldsClient.coordinator().beginWorldSession(worldDir);
        } catch (Exception e) {
            // Fail open: a SharedWorlds error must never crash the game or block a world.
            LOGGER.error("SharedWorlds launch check failed for {}, launching normally", levelName, e);
            allowLaunch = true;
        }

        if (!allowLaunch) {
            LOGGER.info("Launch of {} cancelled by SharedWorlds", levelName);
            ci.cancel();
            // onCancel is Minecraft's own way back: it refreshes the world list, or returns to the
            // title screen for Quick Play. The locked-out screen goes on top and returns to whatever it set.
            onCancel.run();
            SharedWorldsClient.coordinator().takeLastLockout().ifPresent(lockout -> {
                MinecraftClient client = MinecraftClient.getInstance();
                client.setScreen(new LockedOutScreen(lockout, client.currentScreen));
            });
        }
    }
}
