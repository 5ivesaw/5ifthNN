package io.fifthnn.mixin;

import io.fifthnn.FifthNNClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 1. Captures sub-tick raw mouse movement deltas for lossless Phase 1 dataset recording.
 * 2. Applies buttery smooth GCD-quantized sub-frame rotation during Phase 2 AI inference.
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
    @Shadow
    @Final
    private Minecraft minecraft;
    @Shadow
    private double accumulatedDX;
    @Shadow
    private double accumulatedDY;

    @Inject(method = "turnPlayer(D)V", at = @At("HEAD"))
    private void fifthnn$captureAndSmoothMouse(double movementTime, CallbackInfo ci) {
        var tracker = FifthNNClient.getInputTracker();
        if (tracker != null && (accumulatedDX != 0.0 || accumulatedDY != 0.0)) {
            tracker.onRawMouseMove(accumulatedDX, accumulatedDY);
        }
        var ai = FifthNNClient.getAiController();
        var cfg = FifthNNClient.getConfig();
        if (ai != null && cfg != null && ai.isAiEnabled() && !ai.isTakeoverActive()) {
            ai.getExecutor().onRenderFrameTurn(minecraft, movementTime, cfg);
        }
    }
}
