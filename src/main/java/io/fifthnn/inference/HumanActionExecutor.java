package io.fifthnn.inference;

import io.fifthnn.config.FifthNNConfig;
import io.fifthnn.mixin.KeyMappingAccessor;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;

import java.util.Random;

/**
 * Phase 2 Non-Strategy Thin Execution Layer (v0.2).
 *
 * ZERO hardcoded PvP, bridging, or BedWars strategy — 100% of decisions come from the
 * Pure NN's PolicyDecision. Handles purely biological/hardware I/O realism:
 *  - Sub-frame smooth camera rotation distributed across 120-300 FPS render frames
 *    (via MouseHandlerMixin.turnPlayer) instead of choppy 20Hz steps.
 *  - Exact physical mouse GCD sensitivity quantization (matches vanilla mouse hardware).
 *  - Natural human CPS micro-jitter around configured CPS ceiling.
 *  - Masked discrete GUI slot clicks & instant release on emergency stop / HG-DAgger takeover.
 */
public final class HumanActionExecutor {
    private float smoothedYawDelta = 0.0f;
    private float smoothedPitchDelta = 0.0f;
    private float pendingYawToApply = 0.0f;
    private float pendingPitchToApply = 0.0f;

    private long lastLeftClickMs = 0L;
    private long lastRightClickMs = 0L;
    private long nextLeftJitterMs = 0L;
    private long nextRightJitterMs = 0L;
    private int guiClickCooldownTicks = 0;
    private boolean virtualControlsHeld = false;
    private final Random rng = new Random();

    public void execute(Minecraft mc, OnnxPolicyEngine.PolicyDecision dec, FifthNNConfig config) {
        LocalPlayer player = mc.player;
        if (player == null || dec == null) {
            releaseAllControls(mc);
            return;
        }

        if (guiClickCooldownTicks > 0) guiClickCooldownTicks--;

        // Case 1: Container / Shop GUI is open -> execute masked discrete GUI action
        if (mc.gui.screen() instanceof AbstractContainerScreen<?> acs) {
            releaseMovementKeys(mc);
            pendingYawToApply = 0.0f;
            pendingPitchToApply = 0.0f;
            if (dec.guiAction() == 1 && guiClickCooldownTicks == 0) {
                player.closeContainer();
                guiClickCooldownTicks = 4;
            } else if (dec.guiAction() >= 2 && guiClickCooldownTicks == 0) {
                int slotIdx = dec.guiAction() - 2;
                AbstractContainerMenu menu = acs.getMenu();
                if (slotIdx >= 0 && slotIdx < menu.slots.size() && mc.gameMode != null) {
                    mc.gameMode.handleContainerInput(menu.containerId, slotIdx, 0, ContainerInput.PICKUP, player);
                    guiClickCooldownTicks = 3;
                }
            }
            return;
        }

        if (mc.gui.screen() != null) {
            releaseAllControls(mc);
            return;
        }

        virtualControlsHeld = true;
        var opts = mc.options;

        // 1. Movement keys (directly from Pure NN; guard against impossible SPR+SNK vanilla overlap!)
        opts.keyUp.setDown(dec.w());
        opts.keyLeft.setDown(dec.a());
        opts.keyDown.setDown(dec.s());
        opts.keyRight.setDown(dec.d());
        opts.keyJump.setDown(dec.jump());
        opts.keyShift.setDown(dec.sneak());
        opts.keySprint.setDown(dec.sprint() && !dec.sneak());

        // 2. Smooth human-clamped camera rotation queued for sub-frame GCD rendering
        float targetYaw = clamp(dec.deltaYawDeg(), -config.maxYawDegPerTick, config.maxYawDegPerTick);
        float targetPitch = clamp(dec.deltaPitchDeg(), -config.maxPitchDegPerTick, config.maxPitchDegPerTick);
        float alpha = clamp(config.aimSmoothingAlpha, 0.2f, 1.0f);
        smoothedYawDelta = alpha * targetYaw + (1.0f - alpha) * smoothedYawDelta;
        smoothedPitchDelta = alpha * targetPitch + (1.0f - alpha) * smoothedPitchDelta;

        if (config.enableMouseGcdSmoothing) {
            // Queue into sub-frame interpolator applied smoothly on every render frame
            pendingYawToApply = smoothedYawDelta;
            pendingPitchToApply = smoothedPitchDelta;
        } else {
            player.setYRot(player.getYRot() + smoothedYawDelta);
            player.setXRot(clamp(player.getXRot() + smoothedPitchDelta, -90.0f, 90.0f));
        }

        // 3. Hotbar selection
        if (dec.hotbarSlot() >= 0 && dec.hotbarSlot() <= 8) {
            player.getInventory().setSelectedSlot(dec.hotbarSlot());
        }

        // 4. Combat & Use clicks (human CPS cap with organic micro-jitter for 1.8 PvP)
        long now = System.currentTimeMillis();
        long baseIntervalMs = Math.max(55L, 1000L / Math.max(1, config.maxHumanCps));

        if (dec.leftState() == 1) {
            if (now - lastLeftClickMs >= baseIntervalMs + nextLeftJitterMs) {
                triggerKeyTap(opts.keyAttack);
                lastLeftClickMs = now;
                nextLeftJitterMs = rng.nextInt(25) - 8;
            }
            opts.keyAttack.setDown(false);
        } else if (dec.leftState() == 2) {
            opts.keyAttack.setDown(true);
        } else {
            opts.keyAttack.setDown(false);
        }

        if (dec.rightState() == 1) {
            if (now - lastRightClickMs >= baseIntervalMs + nextRightJitterMs) {
                triggerKeyTap(opts.keyUse);
                lastRightClickMs = now;
                nextRightJitterMs = rng.nextInt(25) - 8;
            }
            opts.keyUse.setDown(false);
        } else if (dec.rightState() == 2) {
            opts.keyUse.setDown(true);
        } else {
            opts.keyUse.setDown(false);
        }
    }

    /**
     * Called on every render frame inside MouseHandlerMixin.turnPlayer(double) when AI is active.
     * Distributes the 20Hz tick's rotation delta smoothly across render frames and quantizes to
     * Minecraft's exact mouse sensitivity GCD step so rotations match physical mouse hardware!
     */
    public void onRenderFrameTurn(Minecraft mc, double frameDeltaSec, FifthNNConfig config) {
        if (!virtualControlsHeld || !config.enableMouseGcdSmoothing) return;
        if (mc == null || mc.player == null || mc.gui.screen() != null) return;
        if (Math.abs(pendingYawToApply) < 1e-4f && Math.abs(pendingPitchToApply) < 1e-4f) return;

        // Fraction of one 50ms tick elapsed during this render frame
        float frac = clamp((float) (frameDeltaSec / 0.05), 0.05f, 1.0f);
        float stepYaw = pendingYawToApply * frac;
        float stepPitch = pendingPitchToApply * frac;

        // Quantize to exact Minecraft mouse sensitivity GCD step
        double sens = mc.options.sensitivity().get();
        double f = sens * 0.6 + 0.2;
        double gcd = f * f * f * 1.2;
        if (gcd > 1e-5) {
            stepYaw = (float) (Math.round(stepYaw / gcd) * gcd);
            stepPitch = (float) (Math.round(stepPitch / gcd) * gcd);
        }

        if (Math.abs(stepYaw) > 0.0f || Math.abs(stepPitch) > 0.0f) {
            mc.player.setYRot(mc.player.getYRot() + stepYaw);
            mc.player.setXRot(clamp(mc.player.getXRot() + stepPitch, -90.0f, 90.0f));
            pendingYawToApply -= stepYaw;
            pendingPitchToApply -= stepPitch;
        }
    }

    public void releaseAllControls(Minecraft mc) {
        pendingYawToApply = 0.0f;
        pendingPitchToApply = 0.0f;
        if (!virtualControlsHeld) return;
        releaseMovementKeys(mc);
        if (mc != null && mc.options != null) {
            mc.options.keyAttack.setDown(false);
            mc.options.keyUse.setDown(false);
        }
        smoothedYawDelta = 0.0f;
        smoothedPitchDelta = 0.0f;
        virtualControlsHeld = false;
    }

    private static void releaseMovementKeys(Minecraft mc) {
        if (mc == null || mc.options == null) return;
        var o = mc.options;
        o.keyUp.setDown(false);
        o.keyLeft.setDown(false);
        o.keyDown.setDown(false);
        o.keyRight.setDown(false);
        o.keyJump.setDown(false);
        o.keyShift.setDown(false);
        o.keySprint.setDown(false);
    }

    private static void triggerKeyTap(KeyMapping km) {
        km.setDown(true);
        KeyMappingAccessor acc = (KeyMappingAccessor) km;
        acc.fifthnn$setClickCount(acc.fifthnn$getClickCount() + 1);
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
