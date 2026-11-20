package io.fifthnn.inference;

import io.fifthnn.config.FifthNNConfig;
import io.fifthnn.mixin.KeyMappingAccessor;
import io.fifthnn.schema.FifthNNSchema;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;

/**
 * Phase 2 Non-Strategy Thin Execution Layer.
 *
 * ZERO hardcoded PvP, bridging, or BedWars strategy — 100% of decisions come from the
 * Pure NN's PolicyDecision. This class ONLY handles biological/humanity physics guardrails:
 *  - Clamps turn rate to human maximums & smooths camera delta across ticks.
 *  - Enforces human 1.8 PvP CPS ceiling (never 20-CPS autoclicker).
 *  - Executes masked discrete GUI slot clicks cleanly without screen-resolution fragility.
 *  - Instantly releases ALL virtual keys on emergency stop / ESC / HG-DAgger takeover.
 */
public final class HumanActionExecutor {
    private float smoothedYawDelta = 0.0f;
    private float smoothedPitchDelta = 0.0f;
    private long lastLeftClickMs = 0L;
    private long lastRightClickMs = 0L;
    private int guiClickCooldownTicks = 0;
    private boolean virtualControlsHeld = false;

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
            if (dec.guiAction() == 1 && guiClickCooldownTicks == 0) {
                player.closeContainer();
                guiClickCooldownTicks = 4; // ~200ms human pause after closing shop
            } else if (dec.guiAction() >= 2 && guiClickCooldownTicks == 0) {
                int slotIdx = dec.guiAction() - 2;
                AbstractContainerMenu menu = acs.getMenu();
                if (slotIdx >= 0 && slotIdx < menu.slots.size() && mc.gameMode != null) {
                    mc.gameMode.handleContainerInput(menu.containerId, slotIdx, 0, ContainerInput.PICKUP, player);
                    guiClickCooldownTicks = 3; // ~150ms human shop click cadence
                }
            }
            return;
        }

        // Do not fight pause / chat screens
        if (mc.gui.screen() != null) {
            releaseAllControls(mc);
            return;
        }

        virtualControlsHeld = true;
        var opts = mc.options;

        // 1. Movement keys (directly from Pure NN)
        opts.keyUp.setDown(dec.w());
        opts.keyLeft.setDown(dec.a());
        opts.keyDown.setDown(dec.s());
        opts.keyRight.setDown(dec.d());
        opts.keyJump.setDown(dec.jump());
        opts.keyShift.setDown(dec.sneak());
        opts.keySprint.setDown(dec.sprint());

        // 2. Smooth human-clamped camera rotation
        float targetYaw = clamp(dec.deltaYawDeg(), -config.maxYawDegPerTick, config.maxYawDegPerTick);
        float targetPitch = clamp(dec.deltaPitchDeg(), -config.maxPitchDegPerTick, config.maxPitchDegPerTick);
        float alpha = clamp(config.aimSmoothingAlpha, 0.2f, 1.0f);
        smoothedYawDelta = alpha * targetYaw + (1.0f - alpha) * smoothedYawDelta;
        smoothedPitchDelta = alpha * targetPitch + (1.0f - alpha) * smoothedPitchDelta;

        player.setYRot(player.getYRot() + smoothedYawDelta);
        player.setXRot(clamp(player.getXRot() + smoothedPitchDelta, -90.0f, 90.0f));

        // 3. Hotbar selection
        if (dec.hotbarSlot() >= 0 && dec.hotbarSlot() <= 8) {
            player.getInventory().setSelectedSlot(dec.hotbarSlot());
        }

        // 4. Combat & Use clicks (human CPS cap for 1.8 PvP authenticity)
        long now = System.currentTimeMillis();
        long minIntervalMs = Math.max(50L, 1000L / Math.max(1, config.maxHumanCps));

        if (dec.leftState() == 1) { // tap
            if (now - lastLeftClickMs >= minIntervalMs) {
                triggerKeyTap(opts.keyAttack);
                lastLeftClickMs = now;
            }
            opts.keyAttack.setDown(false);
        } else if (dec.leftState() == 2) { // hold (mining bed / defense)
            opts.keyAttack.setDown(true);
        } else {
            opts.keyAttack.setDown(false);
        }

        if (dec.rightState() == 1) { // tap (place block / bridge / eat / open shop)
            if (now - lastRightClickMs >= minIntervalMs) {
                triggerKeyTap(opts.keyUse);
                lastRightClickMs = now;
            }
            opts.keyUse.setDown(false);
        } else if (dec.rightState() == 2) { // hold (eat gapple / drink potion / bow)
            opts.keyUse.setDown(true);
        } else {
            opts.keyUse.setDown(false);
        }
    }

    public void releaseAllControls(Minecraft mc) {
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
