package com.cbiv.sharedworlds.lifecycle;

import com.cbiv.sharedworlds.SharedWorldsClient;
import com.cbiv.sharedworlds.config.GlobalSharedWorldConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.ProgressScreen;
import net.minecraft.client.gui.screen.world.WorldListWidget;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import net.minecraft.world.level.storage.LevelSummary;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;

/*
Mixin hooks on the world list's Delete.

- deleteIfConfirmed: for a shared world, the confirmation says plainly that only this computer's copy is deleted
  and the shared copy is untouched (vanilla says the world "will be lost forever"). Otherwise it does exactly
  what vanilla does. Unshared worlds get vanilla's screen unchanged.
- delete: afterwards, the world's entry in sharedworlds.json is removed if its folder is really gone, so it can
  be added again later. The shared copy is never deleted from here.
 */
@Mixin(WorldListWidget.WorldEntry.class)
public abstract class WorldDeleteHook {

    @Shadow
    @Final
    private WorldListWidget parent;

    @Shadow
    @Final
    private MinecraftClient client;

    @Shadow
    @Final
    LevelSummary level;

    @Shadow
    public abstract void delete();

    @Inject(method = "deleteIfConfirmed", at = @At("HEAD"), cancellable = true)
    private void sharedworlds$confirmSharedDelete(CallbackInfo ci) {
        Optional<GlobalSharedWorldConfig.SharedWorldEntry> shared =
                SharedWorldsClient.service().findShared(this.level.getName());
        if (shared.isEmpty()) {
            return;
        }
        String location = shared.get().source() == null ? "?" : shared.get().source().location();
        this.client.setScreen(new ConfirmScreen(
                confirmed -> {
                    if (confirmed) {
                        this.client.setScreen(new ProgressScreen(true));
                        this.delete();
                    }
                    this.parent.refresh();
                },
                Text.translatable("selectWorld.deleteQuestion"),
                Text.translatable("sharedworlds.delete.warning", this.level.getDisplayName(), location),
                Text.translatable("sharedworlds.delete.button"),
                ScreenTexts.CANCEL));
        ci.cancel();
    }

    @Inject(method = "delete", at = @At("TAIL"))
    private void sharedworlds$afterDelete(CallbackInfo ci) {
        SharedWorldsClient.service().onLocalWorldDeleted(this.level.getName());
    }
}
