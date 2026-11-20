package io.fifthnn.inference;

import io.fifthnn.recorder.FrameData;
import io.fifthnn.schema.FifthNNSchema;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Deterministic Egocentric Feature Encoder for Phase 2 ONNX inference.
 *
 * CRITICAL: Matches python/fifthnn/features.py bit-for-bit so live ONNX inference
 * receives the EXACT same normalized tensors that the PyTorch model was trained on.
 */
public final class FeatureEncoder {
    private FeatureEncoder() {}

    public record EncodedFeatures(
            long[] voxelCats,      // [1 * 5 * 9 * 9]
            float[] voxelFlags,    // [1 * 2 * 5 * 9 * 9]
            float[] scalarObs      // [1 * 456]
    ) {}

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double[] worldToEgo(double dx, double dz, float yawDeg) {
        double rad = Math.toRadians(yawDeg);
        double sinY = Math.sin(rad);
        double cosY = Math.cos(rad);
        double fwd = dx * (-sinY) + dz * cosY;
        double right = dx * (-cosY) + dz * (-sinY);
        return new double[]{fwd, right};
    }

    public static EncodedFeatures encode(FrameData fr, int serverProfileId, boolean pvpMode18) {
        int vy = FifthNNSchema.NN_VOXEL_SIZE_Y;   // 5
        int vxz = FifthNNSchema.NN_VOXEL_SIZE_XZ; // 9
        int plane = vxz * vxz;
        int vol = vy * plane;

        long[] voxelCats = new long[vol];
        float[] voxelFlags = new float[2 * vol];

        // 1. Rotate 13x7x13 raw voxel cube into egocentric 9x5x9 patch
        double rad = Math.toRadians(fr.yaw);
        double sinY = Math.sin(rad);
        double cosY = Math.cos(rad);
        int rawXZ = FifthNNSchema.RAW_VOXEL_SIZE_XZ;

        for (int yi = 0; yi < vy; yi++) {
            int dy = FifthNNSchema.NN_VOXEL_MIN_Y + yi;
            int rawYi = dy - FifthNNSchema.RAW_VOXEL_MIN_Y;
            for (int fi = 0; fi < vxz; fi++) {
                int fwd = fi - FifthNNSchema.NN_VOXEL_RADIUS_XZ;
                for (int ri = 0; ri < vxz; ri++) {
                    int right = ri - FifthNNSchema.NN_VOXEL_RADIUS_XZ;
                    int wx = (int) Math.round(fwd * (-sinY) + right * (-cosY));
                    int wz = (int) Math.round(fwd * cosY + right * (-sinY));
                    int rawXi = wx + FifthNNSchema.RAW_VOXEL_RADIUS_XZ;
                    int rawZi = wz + FifthNNSchema.RAW_VOXEL_RADIUS_XZ;
                    int dstIdx = (yi * vxz + fi) * vxz + ri;

                    if (rawXi >= 0 && rawXi < rawXZ && rawZi >= 0 && rawZi < rawXZ) {
                        int rawIdx = (rawYi * rawXZ + rawZi) * rawXZ + rawXi;
                        int packed = fr.voxels[rawIdx] & 0xFF;
                        voxelCats[dstIdx] = Math.min(packed & 0x3F, FifthNNSchema.NUM_BLOCK_CATS - 1);
                        voxelFlags[dstIdx] = ((packed & 0x40) != 0) ? 1.0f : 0.0f;
                        voxelFlags[vol + dstIdx] = ((packed & 0x80) != 0) ? 1.0f : 0.0f;
                    }
                }
            }
        }

        // 2. Build 456-float scalar observation vector
        float[] obs = new float[FifthNNSchema.NN_SCALAR_OBS_DIM];
        int off = 0;

        // 2A. 32-ray Panoramic Lidar (128 floats)
        for (int i = 0; i < FifthNNSchema.LIDAR_RAY_COUNT; i++) {
            int lflags = fr.lidarFlags[i] & 0xFF;
            obs[off] = Math.min(fr.lidarDist[i] / 32.0f, 1.0f);
            obs[off + 1] = (fr.lidarCat[i] & 0xFF) / (float) FifthNNSchema.NUM_BLOCK_CATS;
            obs[off + 2] = ((lflags & 0x01) != 0) ? 1.0f : 0.0f;
            obs[off + 3] = ((lflags & 0x04) != 0) ? 1.0f : 0.0f;
            off += 4;
        }

        // 2B. Top-6 Visible Players (108 floats)
        int pCount = Math.min(fr.players.size(), FifthNNSchema.NN_MAX_PLAYERS);
        for (int pi = 0; pi < pCount; pi++) {
            var p = fr.players.get(pi);
            double dx = p.x() - fr.x;
            double dy = p.y() - fr.y;
            double dz = p.z() - fr.z;
            double[] ego = worldToEgo(dx, dz, fr.yaw);
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz) + 1e-6;
            double[] rvel = worldToEgo(p.vx() - fr.vx, p.vz() - fr.vz, fr.yaw);
            double relYaw = Math.toRadians(p.yaw() - fr.yaw);

            double eRad = Math.toRadians(p.yaw());
            double exFwd = -Math.sin(eRad);
            double ezFwd = Math.cos(eRad);
            double lookingAtMe = exFwd * (-dx / dist) + ezFwd * (-dz / dist);

            int base = off + pi * FifthNNSchema.NN_PLAYER_FEAT_DIM;
            int flags = p.flags();
            obs[base] = 1.0f;
            obs[base + 1] = ((flags & 0x01) != 0) ? 1.0f : 0.0f;
            obs[base + 2] = clamp((float) (ego[0] / 32.0), -1.0f, 1.0f);
            obs[base + 3] = clamp((float) (ego[1] / 32.0), -1.0f, 1.0f);
            obs[base + 4] = clamp((float) (dy / 16.0), -1.0f, 1.0f);
            obs[base + 5] = (float) Math.min(dist / 48.0, 1.0);
            obs[base + 6] = clamp((float) rvel[0], -1.0f, 1.0f);
            obs[base + 7] = clamp(p.vy() - fr.vy, -1.0f, 1.0f);
            obs[base + 8] = clamp((float) rvel[1], -1.0f, 1.0f);
            obs[base + 9] = (float) Math.sin(relYaw);
            obs[base + 10] = (float) Math.cos(relYaw);
            obs[base + 11] = clamp((float) lookingAtMe, -1.0f, 1.0f);
            obs[base + 12] = clamp(p.hpEst() / 20.0f, 0.0f, 1.0f);
            obs[base + 13] = Math.min(p.armorTierSum() / 16.0f, 1.0f);
            obs[base + 14] = p.mainHandCat() / (float) FifthNNSchema.NUM_ITEM_CATS;
            obs[base + 15] = ((flags & 0x04) != 0) ? 1.0f : 0.0f;
            obs[base + 16] = ((flags & 0x20) != 0) ? 1.0f : 0.0f;
            obs[base + 17] = ((flags & 0x40) != 0) ? 1.0f : 0.0f;
        }
        off += FifthNNSchema.NN_MAX_PLAYERS * FifthNNSchema.NN_PLAYER_FEAT_DIM;

        // 2C. Top-6 World Threats & Drops (60 floats)
        int tCount = Math.min(fr.threats.size(), FifthNNSchema.NN_MAX_THREATS);
        for (int ti = 0; ti < tCount; ti++) {
            var t = fr.threats.get(ti);
            double dx = t.x() - fr.x;
            double dy = t.y() - fr.y;
            double dz = t.z() - fr.z;
            double[] ego = worldToEgo(dx, dz, fr.yaw);
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            double[] rvel = worldToEgo(t.vx(), t.vz(), fr.yaw);

            int base = off + ti * FifthNNSchema.NN_THREAT_FEAT_DIM;
            obs[base] = 1.0f;
            obs[base + 1] = t.type() / 12.0f;
            obs[base + 2] = clamp((float) (ego[0] / 24.0), -1.0f, 1.0f);
            obs[base + 3] = clamp((float) (ego[1] / 24.0), -1.0f, 1.0f);
            obs[base + 4] = clamp((float) (dy / 16.0), -1.0f, 1.0f);
            obs[base + 5] = (float) Math.min(dist / 32.0, 1.0);
            obs[base + 6] = clamp((float) rvel[0], -1.0f, 1.0f);
            obs[base + 7] = clamp(t.vy(), -1.0f, 1.0f);
            obs[base + 8] = clamp((float) rvel[1], -1.0f, 1.0f);
            obs[base + 9] = (float) Math.min(Math.log1p(Math.max(0.0, t.extra())) / 5.0, 1.0);
        }
        off += FifthNNSchema.NN_MAX_THREATS * FifthNNSchema.NN_THREAT_FEAT_DIM;

        // 2D. Self + Crosshair + Inventory + Match + Sounds + GUI (160 floats)
        int s0 = off;
        double[] egoV = worldToEgo(fr.vx, fr.vz, fr.yaw);

        // Self kinematics & vitals (24)
        obs[s0] = clamp((float) egoV[0], -2.0f, 2.0f);
        obs[s0 + 1] = clamp((float) egoV[1], -2.0f, 2.0f);
        obs[s0 + 2] = clamp(fr.vy, -2.0f, 2.0f);
        obs[s0 + 3] = fr.pitch / 90.0f;
        obs[s0 + 4] = clamp((float) ((fr.y - 30.0) / 80.0), -1.0f, 1.0f);
        obs[s0 + 5] = fr.eyeHeight / 2.0f;
        obs[s0 + 6] = Math.min(fr.fallDist / 10.0f, 1.0f);
        obs[s0 + 7] = clamp(fr.hp / 20.0f, 0.0f, 1.0f);
        obs[s0 + 8] = clamp(fr.absorption / 20.0f, 0.0f, 1.0f);
        obs[s0 + 9] = clamp(fr.hunger / 20.0f, 0.0f, 1.0f);
        obs[s0 + 10] = fr.swingProgress;
        obs[s0 + 11] = fr.attackCooldown;
        obs[s0 + 12] = Math.min(fr.attackSpeedAttr / 24.0f, 1.0f);
        obs[s0 + 13] = ((fr.selfFlags & FifthNNSchema.SFLAG_ON_GROUND) != 0) ? 1.0f : 0.0f;
        obs[s0 + 14] = ((fr.selfFlags & FifthNNSchema.SFLAG_SNEAKING) != 0) ? 1.0f : 0.0f;
        obs[s0 + 15] = ((fr.selfFlags & FifthNNSchema.SFLAG_SPRINTING) != 0) ? 1.0f : 0.0f;
        obs[s0 + 16] = ((fr.selfFlags & FifthNNSchema.SFLAG_IN_WATER) != 0) ? 1.0f : 0.0f;
        obs[s0 + 17] = ((fr.selfFlags & FifthNNSchema.SFLAG_ON_FIRE) != 0) ? 1.0f : 0.0f;
        obs[s0 + 18] = Math.min(fr.hurtTime / 10.0f, 1.0f);
        obs[s0 + 19] = ((fr.effectFlags & 0x01) != 0) ? 1.0f : 0.0f;
        obs[s0 + 20] = ((fr.effectFlags & 0x02) != 0) ? 1.0f : 0.0f;
        obs[s0 + 21] = ((fr.effectFlags & 0x04) != 0) ? 1.0f : 0.0f;
        obs[s0 + 22] = ((fr.effectFlags & 0x08) != 0) ? 1.0f : 0.0f;
        obs[s0 + 23] = pvpMode18 ? 1.0f : 0.0f;

        // Crosshair exact target (14)
        obs[s0 + 24] = (fr.hitType == 1) ? 1.0f : 0.0f;
        obs[s0 + 25] = (fr.hitType == 2) ? 1.0f : 0.0f;
        obs[s0 + 26] = Math.min(fr.hitDist / 6.0f, 1.0f);
        obs[s0 + 27] = fr.hitBlockCat / (float) FifthNNSchema.NUM_BLOCK_CATS;
        obs[s0 + 28] = ((fr.hitFlags & 0x01) != 0) ? 1.0f : 0.0f;
        obs[s0 + 29] = ((fr.hitFlags & 0x02) != 0) ? 1.0f : 0.0f;
        obs[s0 + 30] = ((fr.hitFlags & 0x04) != 0) ? 1.0f : 0.0f;
        obs[s0 + 31] = ((fr.hitFlags & 0x08) != 0) ? 1.0f : 0.0f;
        obs[s0 + 32] = fr.hitFace / 6.0f;
        obs[s0 + 33] = fr.miningProgress;
        obs[s0 + 34] = (fr.hitType == 2 && (fr.hitFlags & 0x10) != 0) ? 1.0f : 0.0f;
        obs[s0 + 35] = (fr.hitType == 2 && (fr.hitFlags & 0x20) != 0) ? 1.0f : 0.0f;
        obs[s0 + 36] = Math.min(fr.pingMs / 250.0f, 1.0f);
        obs[s0 + 37] = Math.min(fr.serverTpsEst / 20.0f, 1.0f);

        // Inventory & Economy (46)
        for (int i = 0; i < 10; i++) {
            obs[s0 + 38 + i] = (float) Math.min(Math.log1p(Math.max(0, fr.econ[i])) / 5.0, 1.0);
        }
        for (int i = 0; i < 6; i++) {
            obs[s0 + 48 + i] = (fr.gearTiers[i] & 0xFF) / 4.0f;
        }
        for (int slot = 0; slot < 9; slot++) {
            obs[s0 + 54 + slot] = (fr.invItemCat[slot] & 0xFF) / (float) FifthNNSchema.NUM_ITEM_CATS;
            obs[s0 + 63 + slot] = (fr.selectedSlot == slot) ? 1.0f : 0.0f;
            obs[s0 + 72 + slot] = Math.min((fr.invCount[slot] & 0xFF) / 64.0f, 1.0f);
        }
        if (fr.selectedSlot >= 0 && fr.selectedSlot < 9) {
            obs[s0 + 81] = (fr.invItemCat[fr.selectedSlot] & 0xFF) / (float) FifthNNSchema.NUM_ITEM_CATS;
            obs[s0 + 82] = Math.min((fr.invCount[fr.selectedSlot] & 0xFF) / 64.0f, 1.0f);
            obs[s0 + 83] = ((fr.invEnchantFlags[fr.selectedSlot] & 0xFF) > 0) ? 1.0f : 0.0f;
        }

        // Match & Landmarks (40)
        obs[s0 + 84] = (float) fr.ownBedAlive;
        obs[s0 + 85] = Integer.bitCount(fr.enemyBedsAliveMask & 0xFF) / 7.0f;
        obs[s0 + 86] = Math.min(fr.enemiesAliveCount / 7.0f, 1.0f);
        for (int i = 0; i < 5; i++) {
            obs[s0 + 87 + i] = (fr.teamUpgrades[i] & 0xFF) / 4.0f;
        }
        obs[s0 + 92] = fr.diamondTier / 3.0f;
        obs[s0 + 93] = fr.emeraldTier / 3.0f;
        obs[s0 + 94] = Math.min(fr.matchElapsedTicks / 12000.0f, 1.0f);
        obs[s0 + 95] = (serverProfileId & 0x7) / 8.0f;

        FrameData.LandmarkEntry lmHome = null, lmDia = null, lmEm = null, lmShop = null, lmUpg = null;
        List<FrameData.LandmarkEntry> enemyBeds = new ArrayList<>();
        for (var lm : fr.landmarks) {
            double dSq = distSq(lm, fr);
            switch (lm.type()) {
                case FifthNNSchema.LM_OWN_BED, FifthNNSchema.LM_HOME_SPAWN -> {
                    if (lmHome == null || lm.type() == FifthNNSchema.LM_OWN_BED) lmHome = lm;
                }
                case FifthNNSchema.LM_ENEMY_BED -> {
                    if (lm.aliveFlag() > 0) enemyBeds.add(lm);
                }
                case FifthNNSchema.LM_DIAMOND_GEN -> {
                    if (lmDia == null || dSq < distSq(lmDia, fr)) lmDia = lm;
                }
                case FifthNNSchema.LM_EMERALD_GEN -> {
                    if (lmEm == null || dSq < distSq(lmEm, fr)) lmEm = lm;
                }
                case FifthNNSchema.LM_SHOP_NPC -> {
                    if (lmShop == null || dSq < distSq(lmShop, fr)) lmShop = lm;
                }
                case FifthNNSchema.LM_UPGRADE_NPC -> {
                    if (lmUpg == null || dSq < distSq(lmUpg, fr)) lmUpg = lm;
                }
                default -> {}
            }
        }
        enemyBeds.sort(Comparator.comparingDouble(l -> distSq(l, fr)));
        writeLm(obs, s0 + 96, lmHome, fr);
        writeLm(obs, s0 + 100, enemyBeds.size() > 0 ? enemyBeds.get(0) : null, fr);
        writeLm(obs, s0 + 104, enemyBeds.size() > 1 ? enemyBeds.get(1) : null, fr);
        writeLm(obs, s0 + 108, lmDia, fr);
        writeLm(obs, s0 + 112, lmEm, fr);
        writeLm(obs, s0 + 116, lmShop, fr);
        writeLm(obs, s0 + 120, lmUpg, fr);

        // Directional Sound Radar (12)
        int sCount = Math.min(fr.sounds.size(), 4);
        for (int si = 0; si < sCount; si++) {
            var s = fr.sounds.get(si);
            double dx = s.x() - fr.x;
            double dz = s.z() - fr.z;
            double[] ego = worldToEgo(dx, dz, fr.yaw);
            double dist = Math.sqrt(dx * dx + dz * dz) + 1e-6;
            obs[s0 + 124 + si * 3] = (s.soundCat() + 1) / 10.0f;
            obs[s0 + 124 + si * 3 + 1] = (float) (ego[0] / dist);
            obs[s0 + 124 + si * 3 + 2] = (float) (ego[1] / dist);
        }

        // GUI & Affordability Context (24)
        obs[s0 + 136] = (fr.guiType > 0) ? 1.0f : 0.0f;
        obs[s0 + 137] = fr.guiType / 8.0f;
        obs[s0 + 138] = fr.shopTab / 8.0f;
        obs[s0 + 139] = fr.guiSlotCount / 54.0f;
        for (int bit = 0; bit < 16; bit++) {
            obs[s0 + 140 + bit] = ((fr.affordableMask & (1 << bit)) != 0) ? 1.0f : 0.0f;
        }
        if (fr.guiSlotCount > 0) {
            int nonEmpty = 0, affCnt = 0;
            for (int gi = 0; gi < fr.guiSlotCount; gi++) {
                if ((fr.guiItemCat[gi] & 0xFF) != 0) nonEmpty++;
                if ((fr.guiFlags[gi] & 0x01) != 0) affCnt++;
            }
            obs[s0 + 156] = nonEmpty / (float) fr.guiSlotCount;
            obs[s0 + 157] = affCnt / (float) fr.guiSlotCount;
        }
        obs[s0 + 158] = (fr.leftClicksThisTick > 0) ? 1.0f : 0.0f;
        obs[s0 + 159] = (fr.rightClicksThisTick > 0) ? 1.0f : 0.0f;

        return new EncodedFeatures(voxelCats, voxelFlags, obs);
    }

    private static double distSq(FrameData.LandmarkEntry lm, FrameData fr) {
        double dx = lm.x() - fr.x;
        double dy = lm.y() - fr.y;
        double dz = lm.z() - fr.z;
        return dx * dx + dy * dy + dz * dz;
    }

    private static void writeLm(float[] obs, int offset, FrameData.LandmarkEntry lm, FrameData fr) {
        if (lm == null) return;
        double dx = lm.x() - fr.x;
        double dy = lm.y() - fr.y;
        double dz = lm.z() - fr.z;
        double[] ego = worldToEgo(dx, dz, fr.yaw);
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz) + 1e-6;
        obs[offset] = 1.0f;
        obs[offset + 1] = (float) Math.min(dist / 128.0, 1.0);
        obs[offset + 2] = (float) (ego[0] / dist);
        obs[offset + 3] = (float) (ego[1] / dist);
    }
}
