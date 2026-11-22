package io.fifthnn.helper;

import io.fifthnn.config.FifthNNConfig;
import io.fifthnn.mixin.KeyMappingAccessor;
import io.fifthnn.mixin.MultiPlayerGameModeAccessor;
import io.fifthnn.recorder.BedWarsStateTracker;
import io.fifthnn.recorder.WorldSnapshotCapture;
import io.fifthnn.schema.FifthNNSchema;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Random;

/**
 * Step 3: Personalized Click-Cadence Hold-to-Click (LMB 5-State & RMB 4-State).
 *
 * Strictly gated by {@link LocalHelperManager}:
 *  - Shifted log-normal + lag-1 autocorrelated ICI sampler (65-180ms, <8% micro-bursts).
 *  - LMB: HOLD_MINE on blocks (3-tick hysteresis), COMBAT_TAP only on entity or <=3.5b +-30 deg LOS
 *    with 1-2 tick transition delay (no 0-tick causal leak!) and 200ms post-kill cutoff.
 *  - RMB: FAST_PLACE only when holding a placeable block & aiming at a non-interactable block
 *    seen for >= 1 tick (no 0-tick causal leak!).
 */
public final class ClickCadenceHelper {
    private final Random rng = new Random();

    private double prevIciMs = 95.0;
    private long nextLmbAllowedMs = 0L;
    private long nextRmbAllowedMs = 0L;

    private long lastMiningActiveTick = -999L;
    private long combatEntryTick = -999L;
    private int combatEntryDelayTicks = 1;
    private long postKillCooldownUntilMs = 0L;
    private int lastTrackedEnemyId = -1;

    private int consecutiveBlockFaceTicks = 0;

    public void reset(Minecraft mc) {
        lastMiningActiveTick = -999L;
        combatEntryTick = -999L;
        postKillCooldownUntilMs = 0L;
        lastTrackedEnemyId = -1;
        consecutiveBlockFaceTicks = 0;
    }

    public void onClientTick(Minecraft mc, FifthNNConfig cfg, BedWarsStateTracker st, long tick) {
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || mc.options == null || mc.gui.screen() != null
                || !player.isAlive() || player.isSpectator()) {
            reset(mc);
            return;
        }

        long nowMs = System.currentTimeMillis();

        // === LMB 5-STATE CONTEXT MACHINE ===
        if (cfg.enableHoldToClickLmb && mc.options.keyAttack.isDown()) {
            if (player.isUsingItem()) {
                combatEntryTick = -999L; // USE_ITEM_LOCKOUT (eating gapple / bowing)
            } else {
                float destroyProg = mc.gameMode != null
                        ? ((MultiPlayerGameModeAccessor) mc.gameMode).fifthnn$getDestroyProgress()
                        : 0.0f;
                if (destroyProg > 0.0f) {
                    lastMiningActiveTick = tick;
                }

                Player targetEnemy = findValidCombatTarget(mc, player, st);
                if (targetEnemy != null) {
                    lastTrackedEnemyId = targetEnemy.getId();
                    if (combatEntryTick < 0) {
                        combatEntryTick = tick;
                        combatEntryDelayTicks = 1 + rng.nextInt(2); // 1-2 tick human reaction (no 0-tick leak!)
                    }
                    if (tick - combatEntryTick >= combatEntryDelayTicks && nowMs >= postKillCooldownUntilMs && nowMs >= nextLmbAllowedMs) {
                        triggerVanillaClick(mc.options.keyAttack);
                        nextLmbAllowedMs = nowMs + sampleNextIciMs(95.0, 0.18, 65.0, 175.0);
                    }
                } else {
                    // Check if previous target just died -> 200ms post-kill cutoff
                    if (lastTrackedEnemyId >= 0) {
                        Entity prev = mc.level.getEntity(lastTrackedEnemyId);
                        if (prev == null || !prev.isAlive()) {
                            postKillCooldownUntilMs = nowMs + 200L;
                        }
                        lastTrackedEnemyId = -1;
                    }
                    combatEntryTick = -999L;
                    // HOLD_MINE or IDLE_AIR: leave vanilla keyAttack.isDown() alone so mining never resets!
                }
            }
        } else {
            combatEntryTick = -999L;
        }

        // === RMB 4-STATE CONTEXT MACHINE ===
        if (cfg.enableHoldToClickRmb && mc.options.keyUse.isDown() && !player.isUsingItem()) {
            int heldCat = WorldSnapshotCapture.classifyItem(player.getMainHandItem());
            if (HotbarHelper.isPlaceableBlock(heldCat) && mc.hitResult instanceof BlockHitResult bhr) {
                Block b = mc.level.getBlockState(bhr.getBlockPos()).getBlock();
                boolean interactable = (b == Blocks.CHEST || b == Blocks.ENDER_CHEST || b == Blocks.TRAPPED_CHEST || b instanceof BedBlock)
                        && !player.isShiftKeyDown();
                if (!interactable) {
                    consecutiveBlockFaceTicks++;
                    // Require >= 1 prior tick seeing block face (no 0-tick causal leak!)
                    if (consecutiveBlockFaceTicks >= 2 && nowMs >= nextRmbAllowedMs) {
                        triggerVanillaClick(mc.options.keyUse);
                        nextRmbAllowedMs = nowMs + sampleNextIciMs(88.0, 0.14, 70.0, 120.0);
                    }
                } else {
                    consecutiveBlockFaceTicks = 0;
                }
            } else {
                consecutiveBlockFaceTicks = 0;
            }
        } else {
            consecutiveBlockFaceTicks = 0;
        }
    }

    private Player findValidCombatTarget(Minecraft mc, LocalPlayer self, BedWarsStateTracker st) {
        int ownTeam = st != null ? st.getOwnTeamId() : 0;
        var cache = st != null ? st.getLandmarkCache() : null;

        // 1. Direct crosshair entity hit
        if (mc.hitResult instanceof EntityHitResult ehr && ehr.getEntity() instanceof Player p) {
            if (isValidEnemy(self, p, ownTeam, cache)) return p;
        }

        // 2. Tightened <= 3.5b, +-30 deg cone WITH strict line-of-sight (never clicks through walls!)
        if (mc.hitResult instanceof BlockHitResult && (st != null && st.getTrackerTickClock() - lastMiningActiveTick <= 3L)) {
            return null; // Mining hysteresis: don't jitter off mining a bed defense
        }
        Vec3 look = self.getViewVector(1.0f).normalize();
        Vec3 eye = self.getEyePosition(1.0f);
        Player best = null;
        double bestDistSq = 3.5 * 3.5;

        for (Entity ent : mc.level.entitiesForRendering()) {
            if (!(ent instanceof Player p) || p == self) continue;
            if (!isValidEnemy(self, p, ownTeam, cache)) continue;
            double dSq = self.distanceToSqr(p);
            if (dSq > bestDistSq) continue;
            Vec3 to = p.getEyePosition(1.0f).subtract(eye).normalize();
            if (look.dot(to) < 0.866) continue; // cos(30 deg) = 0.866
            if (!self.hasLineOfSight(p)) continue;
            bestDistSq = dSq;
            best = p;
        }
        return best;
    }

    private static boolean isValidEnemy(LocalPlayer self, Player p, int ownTeam, io.fifthnn.recorder.ArenaLandmarkCache cache) {
        if (!p.isAlive() || p.isSpectator()) return false;
        if (cache != null && cache.isNearShopNpcHologram(p.getX(), p.getY(), p.getZ())) return false;
        int otherTeam = BedWarsStateTracker.inferTeamIdFromArmor(p);
        if (ownTeam > 0 && otherTeam > 0 && ownTeam == otherTeam) return false;
        if (self.getTeam() != null && self.getTeam().equals(p.getTeam()) && otherTeam == 0) return false;
        return true;
    }

    private long sampleNextIciMs(double medianMs, double sigma, double minMs, double maxMs) {
        // Rare natural human double-click micro-burst (<8%)
        if (rng.nextDouble() < 0.07) {
            return 48L + rng.nextInt(18);
        }
        double logSample = medianMs * Math.exp(rng.nextGaussian() * sigma);
        double blended = 0.35 * prevIciMs + 0.65 * logSample;
        double clamped = Math.max(minMs, Math.min(maxMs, blended));
        prevIciMs = clamped;
        return Math.round(clamped);
    }

    private static void triggerVanillaClick(KeyMapping km) {
        KeyMappingAccessor acc = (KeyMappingAccessor) km;
        acc.fifthnn$setClickCount(acc.fifthnn$getClickCount() + 1);
    }
}
