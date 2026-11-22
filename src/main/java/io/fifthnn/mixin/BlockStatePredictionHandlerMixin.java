package io.fifthnn.mixin;

import io.fifthnn.recorder.ViaFlickerGuard;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Built-in anti-cheat-safe ViaVersion 1-tick block-flicker guard (GPL-3.0).
 *
 * Defers premature ClientboundBlockChangedAckPacket reversions by 3 ticks (150ms) so
 * out-of-order 1.8 server BlockUpdate(WOOL) packets arrive before local predictions revert
 * to AIR. Auto-bypasses completely if ViaFabricPlus is already loaded.
 */
@Mixin(BlockStatePredictionHandler.class)
public abstract class BlockStatePredictionHandlerMixin {
    @Inject(method = "endPredictionsUpTo", at = @At("HEAD"), cancellable = true)
    private void fifthnn$deferViaVersionAck(int seq, ClientLevel level, CallbackInfo ci) {
        if (ViaFlickerGuard.shouldDeferAck()) {
            ViaFlickerGuard.enqueueDeferredAck((BlockStatePredictionHandler) (Object) this, seq, level);
            ci.cancel();
        }
    }
}
