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
import net.minecraft.core.Direction;
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
 * Focused Click-Cadence Helpers (v0.5.0, zero extra keybinds, strictly gated by {@link LocalHelperManager}):
 *
 *  1. LMB Combat Hold-to-Click (`lmb`):
 *     - Shifted log-normal + lag-1 autocorrelated cadence (~9-12 CPS) ONLY when an enemy player
 *       is directly under crosshair or within 3.5b +-30 deg LOS.
 *     - Respects mining hysteresis (never resets bed-defense break progress), item use, and
 *       auto-blockhit pulses (never attacks on the same tick RMB block is held -> AC safe!).
 *
 *  2. RMB Edge-Only Bridge & Clutch Assist (`rmb`):
 *     - NEVER double-places single blocks and NEVER spams TOP faces (Direction.UP).
 *     - Only fires 1 clean click per brand-new horizontal SIDE face (bridging edge or wall clutch)
 *       after deliberate RMB hold (>=4t on ground, >=2t mid-air fall) + >=1t causal visibility.
 */
public final class ClickCadenceHelper {
    private final Random rng = new Random();

    // LMB state
    private double prevLmbIciMs = 92.0;
    private long nextLmbAllowedMs = 0L;
    private long lastMiningActiveTick = -999L;
    private long combatEntryTick = -999L;
    private int combatEntryDelayTicks = 1;
    private long postKillCooldownUntilMs = 0L;
    private int lastTrackedEnemyId = -1;

    // RMB Edge-Only state (strictly 1 click per distinct new side face!)
    private double prevRmbIciMs = 125.0;
    private long nextRmbAllowedMs = 0L;
    private int rmbHoldTicks = 0;
    private long lastSeenTargetKey = Long.MIN_VALUE;
    private int seenTargetTicks = 0;
    private long lastClickedTargetKey = Long.MIN_VALUE;

    public void reset() {
        lastMiningActiveTick = -999L;
        combatEntryTick = -999L;
        postKillCooldownUntilMs = 0L;
        lastTrackedEnemyId = -1;
        rmbHoldTicks = 0;
        lastSeenTargetKey = Long.MIN_VALUE;
        seenTargetTicks = 0;
        lastClickedTargetKey = Long.MIN_VALUE;
    }

    public void onClientTick(
            Minecraft mc,
            FifthNNConfig cfg,
            BedWarsStateTracker st,
            boolean blockhitActive,
            long tick
    ) {
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || mc.options == null || mc.gui.screen() != null
                || !player.isAlive() || player.isSpectator()) {
            reset();
            return;
        }

        long nowMs = System.currentTimeMillis();

        // === 1. LMB COMBAT HOLD-TO-CLICK ===
        if (cfg.enableHoldToClickLmb && mc.options.keyAttack.isDown()) {
            if (player.isUsingItem() || blockhitActive) {
                // Never emit attack click while eating/bowing/blocking (1.8 AC AutoBlock safety!)
                combatEntryTick = -999L;
            } else {
                float destroyProg = mc.gameMode != null
                        ? ((MultiPlayerGameModeAccessor) mc.gameMode).fifthnn$getDestroyProgress()
                        : 0.0f;
                if (destroyProg > 0.0f) {
                    lastMiningActiveTick = tick;
                }

                Player targetEnemy = findValidCombatTarget(mc, player, st, tick);
                if (targetEnemy != null) {
                    lastTrackedEnemyId = targetEnemy.getId();
                    if (combatEntryTick < 0) {
                        combatEntryTick = tick;
                        combatEntryDelayTicks = 1 + rng.nextInt(2); // 1-2t human reaction (zero 0-tick leak)
                    }
                    if (tick - combatEntryTick >= combatEntryDelayTicks
                            && nowMs >= postKillCooldownUntilMs
                            && nowMs >= nextLmbAllowedMs) {
                        triggerVanillaClick(mc.options.keyAttack);
                        nextLmbAllowedMs = nowMs + sampleLmbIciMs();
                    }
                } else {
                    if (lastTrackedEnemyId >= 0) {
                        Entity prev = mc.level.getEntity(lastTrackedEnemyId);
                        if (prev == null || !prev.isAlive()) {
                            postKillCooldownUntilMs = nowMs + 200L;
                        }
                        lastTrackedEnemyId = -1;
                    }
                    combatEntryTick = -999L;
                }
            }
        } else {
            combatEntryTick = -999L;
        }

        // === 2. RMB EDGE-ONLY BRIDGE & CLUTCH PLACEMENT ===
        if (cfg.enableHoldToClickRmb && mc.options.keyUse.isDown() && !player.isUsingItem() && !blockhitActive) {
            rmbHoldTicks++;
            int heldCat = WorldSnapshotCapture.classifyItem(player.getMainHandItem());
            if (isPlaceableBlock(heldCat)
                    && mc.hitResult instanceof BlockHitResult bhr
                    && bhr.getType() == HitResult.Type.BLOCK) {
                Block b = mc.level.getBlockState(bhr.getBlockPos()).getBlock();
                boolean interactable = (b == Blocks.CHEST || b == Blocks.ENDER_CHEST || b == Blocks.TRAPPED_CHEST || b instanceof BedBlock)
                        && !player.isShiftKeyDown();

                // ONLY assist on horizontal SIDE faces (bridging edge / wall clutch) — NEVER spam TOP faces!
                boolean isSideFace = bhr.getDirection().getAxis() != Direction.Axis.Y;
                if (!interactable && isSideFace) {
                    long targetKey = (bhr.getBlockPos().asLong() << 3) | (bhr.getDirection().ordinal() & 0x7L);
                    if (targetKey == lastSeenTargetKey) {
                        seenTargetTicks++;
                    } else {
                        lastSeenTargetKey = targetKey;
                        seenTargetTicks = 1;
                    }

                    boolean midAirClutch = !player.onGround() && player.getDeltaMovement().y < -0.08;
                    int minHoldTicks = midAirClutch ? 2 : 4; // Normal single-block clicks (<200ms) NEVER double-place!

                    // Strictly 1 click per distinct (blockPos, sideFace) + >=1 prior tick visibility!
                    if (rmbHoldTicks >= minHoldTicks
                            && seenTargetTicks >= 2
                            && targetKey != lastClickedTargetKey
                            && nowMs >= nextRmbAllowedMs) {
                        triggerVanillaClick(mc.options.keyUse);
                        lastClickedTargetKey = targetKey;
                        nextRmbAllowedMs = nowMs + sampleRmbIciMs(midAirClutch);
                    }
                } else {
                    lastSeenTargetKey = Long.MIN_VALUE;
                    seenTargetTicks = 0;
                }
            } else {
                lastSeenTargetKey = Long.MIN_VALUE;
                seenTargetTicks = 0;
            }
        } else {
            rmbHoldTicks = 0;
            lastSeenTargetKey = Long.MIN_VALUE;
            seenTargetTicks = 0;
            lastClickedTargetKey = Long.MIN_VALUE;
        }
    }

    private Player findValidCombatTarget(Minecraft mc, LocalPlayer self, BedWarsStateTracker st, long tick) {
        int ownTeam = st != null ? st.getOwnTeamId() : 0;
        var cache = st != null ? st.getLandmarkCache() : null;

        if (mc.hitResult instanceof EntityHitResult ehr && ehr.getEntity() instanceof Player p) {
            if (isValidEnemy(self, p, ownTeam, cache)) return p;
        }

        if (mc.hitResult instanceof BlockHitResult && (tick - lastMiningActiveTick <= 3L)) {
            return null;
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
            if (look.dot(to) < 0.866) continue; // +-30 deg cone
            if (!self.hasLineOfSight(p)) continue;
            bestDistSq = dSq;
            best = p;
        }
        return best;
    }

    static boolean isValidEnemy(LocalPlayer self, Player p, int ownTeam, io.fifthnn.recorder.ArenaLandmarkCache cache) {
        if (!p.isAlive() || p.isSpectator()) return false;
        if (cache != null && cache.isNearShopNpcHologram(p.getX(), p.getY(), p.getZ())) return false;
        int otherTeam = BedWarsStateTracker.inferTeamIdFromArmor(p);
        if (ownTeam > 0 && otherTeam > 0 && ownTeam == otherTeam) return false;
        if (self.getTeam() != null && self.getTeam().equals(p.getTeam()) && otherTeam == 0) return false;
        return true;
    }

    private static boolean isPlaceableBlock(int cat) {
        return cat == FifthNNSchema.ITEM_WOOL || cat == FifthNNSchema.ITEM_TERRACOTTA
                || cat == FifthNNSchema.ITEM_WOOD_PLANKS || cat == FifthNNSchema.ITEM_ENDSTONE
                || cat == FifthNNSchema.ITEM_BLASTPROOF_GLASS || cat == FifthNNSchema.ITEM_OBSIDIAN
                || cat == FifthNNSchema.ITEM_LADDER;
    }

    private long sampleLmbIciMs() {
        if (rng.nextDouble() < 0.07) {
            return 48L + rng.nextInt(18);
        }
        double logSample = 92.0 * Math.exp(rng.nextGaussian() * 0.18);
        double blended = 0.35 * prevLmbIciMs + 0.65 * logSample;
        double clamped = Math.max(65.0, Math.min(170.0, blended));
        prevLmbIciMs = clamped;
        return Math.round(clamped);
    }

    private long sampleRmbIciMs(boolean clutch) {
        double median = clutch ? 88.0 : 128.0;
        double lo = clutch ? 72.0 : 108.0;
        double hi = clutch ? 120.0 : 165.0;
        double logSample = median * Math.exp(rng.nextGaussian() * 0.14);
        double blended = 0.35 * prevRmbIciMs + 0.65 * logSample;
        double clamped = Math.max(lo, Math.min(hi, blended));
        prevRmbIciMs = clamped;
        return Math.round(clamped);
    }

    private static void triggerVanillaClick(KeyMapping km) {
        KeyMappingAccessor acc = (KeyMappingAccessor) km;
        acc.fifthnn$setClickCount(acc.fifthnn$getClickCount() + 1);
    }
}
