package io.fifthnn.helper;

import io.fifthnn.config.FifthNNConfig;
import io.fifthnn.mixin.KeyMappingAccessor;
import io.fifthnn.recorder.BedWarsStateTracker;
import io.fifthnn.recorder.WorldSnapshotCapture;
import io.fifthnn.schema.FifthNNSchema;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.Random;

/**
 * Step 4: Context-Gated 1.8 Combat & Utility Weaves (strictly manual trigger, zero 0-tick leaks).
 *
 * Includes:
 *  - Context-Gated One-Tap W-Tap (only fires in combat, suppresses W for 2-3t, restores physical key!)
 *  - Context-Gated One-Tap Blockhit (sword + combat context, randomized 1-3t pulse)
 *  - Quick Utility Throw-and-Return (strict multi-tick: t=swap -> t+1=RMB -> t+2..4=return)
 */
public final class CombatWeaveHelper {
    private final Random rng = new Random();

    private long lastAttackTick = -999L;

    // W-Tap state
    private int wTapSuppressRemaining = 0;
    private long lastWTapTick = -999L;

    // Blockhit state
    private int blockhitTicksRemaining = 0;
    private long lastBlockhitTick = -999L;

    // Quick Utility Throw-and-Return state machine
    private int utilStage = 0; // 0=idle, 1=wait_rmb, 2=wait_return
    private int utilTargetSlot = -1;
    private int utilReturnSlot = -1;
    private long utilNextActionTick = -1L;

    public void onMeleeAttack(long tick) {
        lastAttackTick = tick;
    }

    public void reset(Minecraft mc) {
        if (mc != null && mc.options != null) {
            if (wTapSuppressRemaining > 0) {
                mc.options.keyUp.setDown(LocalHelperManager.isPhysicallyHeld(mc, mc.options.keyUp));
            }
            if (blockhitTicksRemaining > 0) {
                mc.options.keyUse.setDown(LocalHelperManager.isPhysicallyHeld(mc, mc.options.keyUse));
            }
        }
        wTapSuppressRemaining = 0;
        blockhitTicksRemaining = 0;
        utilStage = 0;
        utilTargetSlot = -1;
        utilReturnSlot = -1;
    }

    public void triggerWTap(Minecraft mc, FifthNNConfig cfg, BedWarsStateTracker st, long tick) {
        if (!cfg.enableContextWTap || mc == null || mc.player == null || mc.level == null || mc.gui.screen() != null) return;
        LocalPlayer p = mc.player;
        if (!mc.options.keyUp.isDown() || mc.options.keyShift.isDown()) return;
        if (tick - lastWTapTick < 8L) return;

        // Void-jump safety: never cut W mid-air over a drop
        if (!p.onGround()) return;

        // Combat context gate: enemy within 5.0b in +-45 deg cone AND (recent hit or <= 3.8b)
        Player enemy = findEnemyInFront(mc, p, st, 5.0, 0.707);
        if (enemy == null) return;
        double dist = p.distanceTo(enemy);
        if ((tick - lastAttackTick > 5L) && dist > 3.8) return;

        wTapSuppressRemaining = 2 + rng.nextInt(2); // 2-3 ticks (100-150ms)
        lastWTapTick = tick;
        mc.options.keyUp.setDown(false);
    }

    public void triggerBlockhit(Minecraft mc, FifthNNConfig cfg, BedWarsStateTracker st, long tick) {
        if (!cfg.enableContextBlockhit || mc == null || mc.player == null || mc.level == null || mc.gui.screen() != null) return;
        LocalPlayer p = mc.player;
        if (p.isUsingItem() || tick - lastBlockhitTick < 8L) return;
        int heldCat = WorldSnapshotCapture.classifyItem(p.getMainHandItem());
        if (heldCat < FifthNNSchema.ITEM_WOOD_SWORD || heldCat > FifthNNSchema.ITEM_DIAMOND_SWORD) return;
        if (tick - lastAttackTick > 4L) return;
        if (findEnemyInFront(mc, p, st, 4.2, 0.707) == null) return;

        blockhitTicksRemaining = 1 + rng.nextInt(3); // 1-3 ticks (50-150ms)
        lastBlockhitTick = tick;
        mc.options.keyUse.setDown(true);
        KeyMappingAccessor acc = (KeyMappingAccessor) mc.options.keyUse;
        acc.fifthnn$setClickCount(acc.fifthnn$getClickCount() + 1);
    }

    /** Strict multi-tick throw: tick t=swap -> t+1=RMB -> t+2..4=return (aborts if GUI opens). */
    public void triggerQuickUtility(Minecraft mc, FifthNNConfig cfg, int itemCat, long tick) {
        if (!cfg.enableQuickUtilityThrow || utilStage != 0) return;
        if (mc == null || mc.player == null || mc.gui.screen() != null) return;
        Inventory inv = mc.player.getInventory();
        int slot = HotbarHelper.findSlotByCat(inv, itemCat);
        if (slot < 0) return;

        utilReturnSlot = inv.getSelectedSlot();
        utilTargetSlot = slot;
        HotbarHelper.selectSlotSafe(inv, slot);
        utilStage = 1;
        utilNextActionTick = tick + 1L; // strictly t+1 before RMB click!
    }

    public void onClientTick(Minecraft mc, FifthNNConfig cfg, BedWarsStateTracker st, long tick) {
        if (mc == null || mc.player == null || mc.options == null) return;

        // Maintain W-Tap suppression, then restore exact physical W key state (never leaves W stuck false!)
        if (wTapSuppressRemaining > 0) {
            wTapSuppressRemaining--;
            if (wTapSuppressRemaining > 0) {
                mc.options.keyUp.setDown(false);
            } else {
                mc.options.keyUp.setDown(LocalHelperManager.isPhysicallyHeld(mc, mc.options.keyUp));
            }
        }

        // Maintain Blockhit pulse, then restore exact physical RMB state
        if (blockhitTicksRemaining > 0) {
            blockhitTicksRemaining--;
            if (blockhitTicksRemaining == 0) {
                mc.options.keyUse.setDown(LocalHelperManager.isPhysicallyHeld(mc, mc.options.keyUse));
            }
        }

        // Advance Quick Utility Throw-and-Return state machine
        if (utilStage > 0) {
            if (mc.gui.screen() != null || mc.player.getInventory().getSelectedSlot() != utilTargetSlot) {
                utilStage = 0; // Abort cleanly if GUI opened (e.g. clicked Shop NPC) or player swapped!
                return;
            }
            if (tick >= utilNextActionTick) {
                if (utilStage == 1) {
                    KeyMappingAccessor acc = (KeyMappingAccessor) mc.options.keyUse;
                    acc.fifthnn$setClickCount(acc.fifthnn$getClickCount() + 1);
                    utilStage = 2;
                    utilNextActionTick = tick + 1L + rng.nextInt(3); // t+2..4 return!
                } else if (utilStage == 2) {
                    HotbarHelper.selectSlotSafe(mc.player.getInventory(), utilReturnSlot);
                    utilStage = 0;
                }
            }
        }
    }

    private static Player findEnemyInFront(Minecraft mc, LocalPlayer self, BedWarsStateTracker st, double maxDist, double minDot) {
        int ownTeam = st != null ? st.getOwnTeamId() : 0;
        var cache = st != null ? st.getLandmarkCache() : null;
        Vec3 look = self.getViewVector(1.0f).normalize();
        Vec3 eye = self.getEyePosition(1.0f);
        double maxSq = maxDist * maxDist;

        for (Entity ent : mc.level.entitiesForRendering()) {
            if (!(ent instanceof Player p) || p == self || !p.isAlive() || p.isSpectator()) continue;
            if (cache != null && cache.isNearShopNpcHologram(p.getX(), p.getY(), p.getZ())) continue;
            int otherTeam = BedWarsStateTracker.inferTeamIdFromArmor(p);
            if (ownTeam > 0 && otherTeam > 0 && ownTeam == otherTeam) continue;
            if (self.distanceToSqr(p) > maxSq) continue;
            Vec3 to = p.getEyePosition(1.0f).subtract(eye).normalize();
            if (look.dot(to) >= minDot && self.hasLineOfSight(p)) {
                return p;
            }
        }
        return null;
    }
}
