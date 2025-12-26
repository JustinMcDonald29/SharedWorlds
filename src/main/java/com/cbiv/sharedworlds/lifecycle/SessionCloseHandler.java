package com.cbiv.sharedworlds.lifecycle;

import com.cbiv.sharedworlds.WorldSessionContext;
import net.minecraft.world.level.storage.LevelStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.file.Path;

@Mixin(LevelStorage.Session.class)
public class SessionCloseHandler {

    @Unique private static final Logger LOGGER = LoggerFactory.getLogger("SessionCloseHandler");

    @Inject(method = "close", at = @At("HEAD"))
    private void onSessionClose(CallbackInfo ci){
        if(!WorldSessionContext.isServerStarted()){ return; }
        Path worldDir = WorldSessionContext.getWorldDirectory();
        if (worldDir != null){
            WorldSessionContext.clear();
            LOGGER.info("Session for world at {} closed", worldDir);
        }

    }
}
