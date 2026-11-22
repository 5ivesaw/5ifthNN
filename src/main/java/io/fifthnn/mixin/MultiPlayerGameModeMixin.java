package io.fifthnn.mixin;

import io.fifthnn.FifthNNClient;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures exact BedWars shop & inventory slot clicks and melee attacks for Phase 1 recording.
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class MultiPlayerGameModeMixin {
    @Inject(method = "handleContainerInput", at = @At("HEAD"))
    private void fifthnn$captureContainerClick(
            int containerId, int slotId, int mouseButton, ContainerInput clickType, Player player, CallbackInfo ci
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

    @Inject(method = "attack", at = @At("HEAD"))
    private void fifthnn$captureAttack(Player player, Entity targetEntity, CallbackInfo ci) {
        var state = FifthNNClient.getStateTracker();
        if (state != null && targetEntity != null) {
            state.markDamageDealt();
        }
    }
}
