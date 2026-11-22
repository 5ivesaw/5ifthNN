package io.fifthnn.helper;

import io.fifthnn.config.FifthNNConfig;
import io.fifthnn.mixin.KeyMappingAccessor;
import io.fifthnn.recorder.BedWarsStateTracker;
import io.fifthnn.recorder.WorldSnapshotCapture;
import io.fifthnn.schema.FifthNNSchema;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.Random;

/**
 * Automatic Post-Hit 1.8 PvP Sprint-Reset (W-Tap) & Close-Trade Sword Blockhit (v0.5.0).
 *
 * ZERO extra keybinds required — works seamlessly from your normal melee attacks!
 *  - Strictly 1-2 tick post-hit reaction delay (zero 0-tick causal leak for 5ifthNN!).
 *  - Cooldowns matched to 1.8's 10-tick damage invulnerability window so you W-tap / blockhit
 *    once per real hit rather than spamming on every swing.
 *  - Restores exact physical W / RMB hardware state via SDL3 when each pulse finishes.
 */
public final class CombatWeaveHelper {
    private final Random rng = new Random();

    // Pending post-hit triggers (strictly t+1 or t+2 after AttackEntityCallback!)
    private long pendingHitTick = -1L;
    private int pendingHitTargetId = -1;
    private long scheduledWTapTick = -1L;
    private long scheduledBlockhitTick = -1L;

    // Active pulse states
    private int wTapSuppressRemaining = 0;
    private long nextWTapAllowedTick = -999L;

    private int blockhitTicksRemaining = 0;
    private long nextBlockhitAllowedTick = -999L;

    public boolean isBlockhitting() {
        return blockhitTicksRemaining > 0;
    }

    public void onMeleeAttack(long tick, Entity target) {
        if (!(target instanceof Player)) return;
        pendingHitTick = tick;
        pendingHitTargetId = target.getId();
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
        pendingHitTick = -1L;
        pendingHitTargetId = -1;
        scheduledWTapTick = -1L;
        scheduledBlockhitTick = -1L;
        wTapSuppressRemaining = 0;
        blockhitTicksRemaining = 0;
    }

    public void onClientTick(Minecraft mc, FifthNNConfig cfg, BedWarsStateTracker st, long tick) {
        if (mc == null || mc.player == null || mc.level == null || mc.options == null) return;
        LocalPlayer p = mc.player;

        if (mc.gui.screen() != null || !p.isAlive() || p.isSpectator()) {
            reset(mc);
            return;
        }

        // 1. Evaluate a freshly landed melee hit -> schedule 1-2t human reaction (zero 0-tick leak!)
        if (pendingHitTick >= 0 && tick >= pendingHitTick) {
            int tid = pendingHitTargetId;
            pendingHitTick = -1L;
            pendingHitTargetId = -1;

            Entity ent = mc.level.getEntity(tid);
            if (ent instanceof Player enemy && ClickCadenceHelper.isValidEnemy(p, enemy, st != null ? st.getOwnTeamId() : 0, st != null ? st.getLandmarkCache() : null)) {
                double dist = p.distanceTo(enemy);

                // Automatic 1.8 W-Tap: on ground, moving forward with W, not sneaking, melee range
                if (cfg.enableContextWTap
                        && scheduledWTapTick < 0
                        && wTapSuppressRemaining == 0
                        && tick >= nextWTapAllowedTick
                        && p.onGround()
                        && mc.options.keyUp.isDown()
                        && !mc.options.keyShift.isDown()
                        && dist <= 3.9) {
                    scheduledWTapTick = tick + 1L + rng.nextInt(2); // t+1 or t+2
                }

                // Automatic 1.8 Close-Trade Blockhit: holding Sword, close trade (<=3.3b), enemy facing/trading
                int heldCat = WorldSnapshotCapture.classifyItem(p.getMainHandItem());
                boolean holdingSword = heldCat >= FifthNNSchema.ITEM_WOOD_SWORD && heldCat <= FifthNNSchema.ITEM_DIAMOND_SWORD;
                if (cfg.enableContextBlockhit
                        && holdingSword
                        && !p.isUsingItem()
                        && scheduledBlockhitTick < 0
                        && blockhitTicksRemaining == 0
                        && tick >= nextBlockhitAllowedTick
                        && dist <= 3.3
                        && isEnemyTrading(p, enemy)
                        && rng.nextDouble() < 0.78) { // natural ~78% close-trade cadence
                    scheduledBlockhitTick = tick + 1L + rng.nextInt(2); // t+1 or t+2
                }
            }
        }

        // 2. Fire scheduled W-Tap pulse (2-3 ticks = 100-150ms clean sprint reset)
        if (scheduledWTapTick >= 0 && tick >= scheduledWTapTick) {
            scheduledWTapTick = -1L;
            if (cfg.enableContextWTap && p.onGround() && mc.options.keyUp.isDown() && !mc.options.keyShift.isDown()) {
                wTapSuppressRemaining = 2 + rng.nextInt(2); // 2-3 ticks
                nextWTapAllowedTick = tick + 9L + rng.nextInt(4); // 9-12t (matches 1.8 10t hurtTime!)
                mc.options.keyUp.setDown(false);
            }
        }

        // 3. Fire scheduled Blockhit pulse (1-2 ticks = 50-100ms clean sword block)
        if (scheduledBlockhitTick >= 0 && tick >= scheduledBlockhitTick) {
            scheduledBlockhitTick = -1L;
            int heldCat = WorldSnapshotCapture.classifyItem(p.getMainHandItem());
            boolean holdingSword = heldCat >= FifthNNSchema.ITEM_WOOD_SWORD && heldCat <= FifthNNSchema.ITEM_DIAMOND_SWORD;
            if (cfg.enableContextBlockhit && holdingSword && !p.isUsingItem()) {
                blockhitTicksRemaining = 1 + rng.nextInt(2); // 1-2 ticks (crisp 1.8 blockhit, minimal slowdown!)
                nextBlockhitAllowedTick = tick + 10L + rng.nextInt(5); // 10-14t
                mc.options.keyUse.setDown(true);
                KeyMappingAccessor acc = (KeyMappingAccessor) mc.options.keyUse;
                acc.fifthnn$setClickCount(acc.fifthnn$getClickCount() + 1);
            }
        }

        // 4. Maintain W-Tap suppression, then restore exact physical W key state
        if (wTapSuppressRemaining > 0) {
            wTapSuppressRemaining--;
            if (wTapSuppressRemaining > 0) {
                mc.options.keyUp.setDown(false);
            } else {
                mc.options.keyUp.setDown(LocalHelperManager.isPhysicallyHeld(mc, mc.options.keyUp));
            }
        }

        // 5. Maintain Blockhit pulse, then restore exact physical RMB state
        if (blockhitTicksRemaining > 0) {
            blockhitTicksRemaining--;
            if (blockhitTicksRemaining == 0) {
                mc.options.keyUse.setDown(LocalHelperManager.isPhysicallyHeld(mc, mc.options.keyUse));
            }
        }
    }

    /** True if the enemy is actively facing us (close melee trade) rather than running away. */
    private static boolean isEnemyTrading(LocalPlayer self, Player enemy) {
        if (self.hurtTime > 0) return true; // actively taking hits -> trade!
        Vec3 enemyLook = enemy.getViewVector(1.0f).normalize();
        Vec3 enemyToSelf = self.getEyePosition(1.0f).subtract(enemy.getEyePosition(1.0f)).normalize();
        return enemyLook.dot(enemyToSelf) > 0.35; // enemy facing within ~70 deg of us
    }
}
