package com.cbiv.sharedworlds.lifecycle;

import com.cbiv.sharedworlds.SharedWorldsClient;
import com.cbiv.sharedworlds.WorldRuntimeCoordinator;
import net.minecraft.world.level.storage.LevelStorage;
//import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
//import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.file.Path;

@Mixin(LevelStorage.Session.class)
public class SessionCloseHandler {

    @Unique
    private static final Logger LOGGER = LoggerFactory.getLogger("SessionCloseHandler");
    @Unique
    WorldRuntimeCoordinator coordinator = SharedWorldsClient.coordinator();

    @Inject(method = "close", at = @At("HEAD"))
    private void onSessionClose(CallbackInfo ci){

        if(!coordinator.isServerStarted()){ return; }
        Path worldDir = coordinator.getWorldDirectory();
        if (coordinator.isWorldActive()){
            coordinator.endWorldSession();
            LOGGER.info("Session for world at {} closed", worldDir);
        }

    }
}
