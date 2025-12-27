package com.cbiv.sharedworlds.lifecycle;

import com.cbiv.sharedworlds.SharedWorldsClient;
import com.cbiv.sharedworlds.WorldRuntimeCoordinator;
import net.minecraft.server.integrated.IntegratedServer;
//import net.minecraft.world.World;
//import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
//import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(IntegratedServer.class)
public class WorldCloseHandler {

    @Unique
    private static final Logger LOGGER = LoggerFactory.getLogger("WorldCloseHandler");
    @Unique
    WorldRuntimeCoordinator coordinator = SharedWorldsClient.coordinator();

    @Inject(method = "setupServer", at = @At("TAIL"))
    private void onServerStarted(CallbackInfoReturnable<Boolean> cir){

        if (cir.getReturnValue()){
            LOGGER.info("IntegratedServer setup complete");
            coordinator.onServerStarted();
        }
    }

    @Inject(method = "shutdown", at = @At("HEAD"))
    private void onServerShutdown(CallbackInfo ci){
        LOGGER.info("IntegratedServer shutting down");
        coordinator.onServerStopping();
    }
}

