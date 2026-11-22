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
 * Automatic Server-Confirmed 1.8 Sprint-Reset (W-Tap) & Close-Trade Sword Blockhit (v0.5.1).
 *
 * Key 1.8 mechanics fixed via MC 26.3 handleKeybinds() & Pika tick forensics:
 *  1. Triggers strictly on server-confirmed hit registration (enemy.hurtTime rising edge >= 8
 *     within 6 ticks of your melee swing) — ZERO wasted resets on 1.8 i-frame swings, 100% ping-proof,
 *     and 100% causally visible to 5ifthNN via obs.enemy_hurt!
 *  2. Mutually exclusive per hit: close sword trade (<=3.1b) uses Blockhit (which already resets
 *     sprint + cuts damage 50%); spacing/combo hits use W-Tap — never double-kills momentum!
 *  3. Active pulses decrement ONLY on subsequent ticks (never same-tick!) and keep LMB locked out
 *     on both the start tick and release tick so Attack + UseItem never collide in handleKeybinds().
 */
public final class CombatWeaveHelper {
    private final Random rng = new Random();

    // Server-confirmed hit tracker
    private int lastAttackedEnemyId = -1;
    private long lastAttackTick = -999L;
    private int prevTrackedHurtTime = 0;

    // Scheduled reactions (strictly t+1 after hurtTime rising edge -> zero 0-tick leak!)
    private long scheduledWTapTick = -1L;
    private long scheduledBlockhitTick = -1L;

    // Active pulse states
    private int wTapSuppressRemaining = 0;
    private long nextWTapAllowedTick = -999L;

    private int blockhitTicksRemaining = 0;
    private long blockhitLmbLockoutUntilTick = -999L;
    private long nextBlockhitAllowedTick = -999L;

    /**
     * True whenever a blockhit is about to start, actively holding RMB, or on its 1-tick release transition.
     * ClickCadenceHelper pauses LMB while this is true so Attack and UseItem never overlap in handleKeybinds().
     */
    public boolean isBlockhitBusy(long tick) {
        return blockhitTicksRemaining > 0
                || tick <= blockhitLmbLockoutUntilTick
                || (scheduledBlockhitTick >= 0 && tick >= scheduledBlockhitTick - 1L);
    }

    public void onMeleeAttack(long tick, Entity target) {
        if (!(target instanceof Player p)) return;
        if (p.getId() != lastAttackedEnemyId) {
            lastAttackedEnemyId = p.getId();
            prevTrackedHurtTime = p.hurtTime;
        }
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
        lastAttackedEnemyId = -1;
        lastAttackTick = -999L;
        prevTrackedHurtTime = 0;
        scheduledWTapTick = -1L;
        scheduledBlockhitTick = -1L;
        wTapSuppressRemaining = 0;
        blockhitTicksRemaining = 0;
        blockhitLmbLockoutUntilTick = -999L;
    }

    public void onClientTick(Minecraft mc, FifthNNConfig cfg, BedWarsStateTracker st, long tick) {
        if (mc == null || mc.player == null || mc.level == null || mc.options == null) return;
        LocalPlayer p = mc.player;

        if (mc.gui.screen() != null || !p.isAlive() || p.isSpectator()) {
            reset(mc);
            return;
        }

        // === PHASE 1: Maintain already-active pulses FIRST (never decrements a pulse started on this tick!) ===
        boolean wTapWasActive = wTapSuppressRemaining > 0;
        if (wTapWasActive) {
            wTapSuppressRemaining--;
            if (wTapSuppressRemaining > 0) {
                mc.options.keyUp.setDown(false);
            } else {
                mc.options.keyUp.setDown(LocalHelperManager.isPhysicallyHeld(mc, mc.options.keyUp));
            }
        }

        boolean blockhitWasActive = blockhitTicksRemaining > 0;
        if (blockhitWasActive) {
            blockhitTicksRemaining--;
            if (blockhitTicksRemaining == 0) {
                mc.options.keyUse.setDown(LocalHelperManager.isPhysicallyHeld(mc, mc.options.keyUse));
                // Keep LMB paused for 1 extra tick while handleKeybinds() processes releaseUsingItem()!
                blockhitLmbLockoutUntilTick = tick + 1L;
            }
        }

        // === PHASE 2: Detect server-confirmed hit registration (enemy.hurtTime rising edge >= 8) ===
        if (lastAttackedEnemyId >= 0) {
            Entity ent = mc.level.getEntity(lastAttackedEnemyId);
            if (!(ent instanceof Player enemy) || !enemy.isAlive() || (tick - lastAttackTick) > 12L) {
                lastAttackedEnemyId = -1;
                prevTrackedHurtTime = 0;
            } else {
                int curHurt = enemy.hurtTime;
                boolean hitConfirmedNow = (curHurt >= 8 && prevTrackedHurtTime < 8) && (tick - lastAttackTick <= 6L);
                prevTrackedHurtTime = curHurt;

                if (hitConfirmedNow
                        && ClickCadenceHelper.isValidEnemy(p, enemy, st != null ? st.getOwnTeamId() : 0, st != null ? st.getLandmarkCache() : null)) {
                    double dist = p.distanceTo(enemy);
                    int heldCat = WorldSnapshotCapture.classifyItem(p.getMainHandItem());
                    boolean holdingSword = heldCat >= FifthNNSchema.ITEM_WOOD_SWORD && heldCat <= FifthNNSchema.ITEM_DIAMOND_SWORD;

                    // Mutually exclusive per hit: Blockhit on close sword trade, else W-Tap for full-speed sprint reset!
                    boolean didScheduleBlockhit = false;
                    if (cfg.enableContextBlockhit
                            && holdingSword
                            && !p.isUsingItem()
                            && !blockhitWasActive
                            && scheduledBlockhitTick < 0
                            && tick >= nextBlockhitAllowedTick
                            && dist <= 3.1
                            && isEnemyTrading(p, enemy)
                            && rng.nextDouble() < 0.78) {
                        scheduledBlockhitTick = tick + 1L; // strictly t+1 after obs.enemy_hurt turns 1!
                        didScheduleBlockhit = true;
                    }

                    if (!didScheduleBlockhit
                            && cfg.enableContextWTap
                            && !wTapWasActive
                            && !blockhitWasActive
                            && scheduledWTapTick < 0
                            && tick >= nextWTapAllowedTick
                            && p.onGround()
                            && mc.options.keyUp.isDown()
                            && !mc.options.keyShift.isDown()
                            && dist <= 3.9) {
                        scheduledWTapTick = tick + 1L; // strictly t+1 after obs.enemy_hurt turns 1!
                    }
                }
            }
        }

        // === PHASE 3: Start newly scheduled pulses (they will run through handleKeybinds() next tick!) ===
        if (!wTapWasActive && scheduledWTapTick >= 0 && tick >= scheduledWTapTick) {
            scheduledWTapTick = -1L;
            if (cfg.enableContextWTap && !blockhitWasActive && blockhitTicksRemaining == 0
                    && p.onGround() && mc.options.keyUp.isDown() && !mc.options.keyShift.isDown()) {
                wTapSuppressRemaining = 2 + rng.nextInt(2); // true 2-3 game ticks (100-150ms)
                nextWTapAllowedTick = tick + 8L;
                mc.options.keyUp.setDown(false);
            }
        }

        if (!blockhitWasActive && scheduledBlockhitTick >= 0 && tick >= scheduledBlockhitTick) {
            scheduledBlockhitTick = -1L;
            int heldCat = WorldSnapshotCapture.classifyItem(p.getMainHandItem());
            boolean holdingSword = heldCat >= FifthNNSchema.ITEM_WOOD_SWORD && heldCat <= FifthNNSchema.ITEM_DIAMOND_SWORD;
            if (cfg.enableContextBlockhit && holdingSword && !p.isUsingItem()) {
                blockhitTicksRemaining = 1 + rng.nextInt(2); // true 1-2 game ticks of blocking (50-100ms)
                nextBlockhitAllowedTick = tick + 9L + rng.nextInt(3);
                mc.options.keyUse.setDown(true);
                KeyMappingAccessor acc = (KeyMappingAccessor) mc.options.keyUse;
                acc.fifthnn$setClickCount(acc.fifthnn$getClickCount() + 1);
            }
        }
    }

    /** True if the enemy is actively facing/trading with us rather than running away. */
    private static boolean isEnemyTrading(LocalPlayer self, Player enemy) {
        if (self.hurtTime > 0) return true;
        Vec3 enemyLook = enemy.getViewVector(1.0f).normalize();
        Vec3 enemyToSelf = self.getEyePosition(1.0f).subtract(enemy.getEyePosition(1.0f)).normalize();
        return enemyLook.dot(enemyToSelf) > 0.35;
    }
}
