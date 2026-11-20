package io.fifthnn.mixin;

import io.fifthnn.FifthNNClient;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures exact BedWars shop & inventory slot clicks for Phase 1 dataset recording.
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class MultiPlayerGameModeMixin {
    @Inject(method = "handleInventoryMouseClick", at = @At("HEAD"), require = 0)
    private void fifthnn$captureContainerClick(
            int containerId, int slotId, int mouseButton, ClickType clickType, Player player, CallbackInfo ci
    ) {
        var tracker = FifthNNClient.getInputTracker();
        if (tracker != null) {
            tracker.onGuiSlotClick(slotId, mouseButton, clickType != null ? clickType.ordinal() : 0);
        }
        var state = FifthNNClient.getStateTracker();
        if (state != null && slotId >= 0) {
            state.markItemBought();
        }
    }
}
