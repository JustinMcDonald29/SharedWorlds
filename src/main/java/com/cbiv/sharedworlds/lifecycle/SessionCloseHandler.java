package com.cbiv.sharedworlds.lifecycle;

import com.cbiv.sharedworlds.SharedWorldsClient;
import com.cbiv.sharedworlds.WorldRuntimeCoordinator;
import net.minecraft.world.level.storage.LevelStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/*
Detects when the active world's LevelStorage.Session closes. For a world that was played, this happens after
the integrated server has stopped and Minecraft has finished saving, so the world folder is safe to work with
(e.g. to copy it back to its source). It also fires if a launch is abandoned before its server starts.

Minecraft opens and closes sessions for many reasons, so only the session for the active world ends the
SharedWorlds session.
 */
@Mixin(LevelStorage.Session.class)
public class SessionCloseHandler {

    @Unique
    private static final Logger LOGGER = LoggerFactory.getLogger("SessionCloseHandler");

    @Inject(method = "close", at = @At("HEAD"))
    private void onSessionClose(CallbackInfo ci) {
        WorldRuntimeCoordinator coordinator = SharedWorldsClient.coordinator();
        String directoryName = ((LevelStorage.Session) (Object) this).getDirectoryName();
        if (!coordinator.isActiveWorld(directoryName)) {
            return;
        }

        boolean played = coordinator.hasServerRun();
        coordinator.endWorldSession();
        if (played) {
            LOGGER.info("Session for world {} closed", directoryName);
        } else {
            LOGGER.info("Session for world {} closed before its server started (launch abandoned)", directoryName);
        }
    }
}
