package io.fifthnn.mixin;

import io.fifthnn.FifthNNClient;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures sub-tick raw mouse movement deltas for lossless Phase 1 dataset recording.
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
    @Shadow
    private double accumulatedDX;
    @Shadow
    private double accumulatedDY;

    @Inject(method = "turnPlayer", at = @At("HEAD"), require = 0)
    private void fifthnn$captureMouseDelta(CallbackInfo ci) {
        var tracker = FifthNNClient.getInputTracker();
        if (tracker != null && (accumulatedDX != 0.0 || accumulatedDY != 0.0)) {
            tracker.onRawMouseMove(accumulatedDX, accumulatedDY);
        }
    }
}
