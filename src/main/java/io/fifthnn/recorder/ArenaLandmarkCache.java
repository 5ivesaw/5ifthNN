package io.fifthnn.recorder;

import io.fifthnn.schema.FifthNNSchema;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Persistent per-match BedWars arena memory (zero FPS impact):
 *  1. Lazy ring scanner discovers ALL beds across loaded chunks (±48 blocks) and detects when beds break.
 *  2. ArmorStand hologram scanner discovers Diamond/Emerald generators and Item/Upgrade Shop NPCs.
 *  3. Fake-player NPC blocker prevents shopkeeper Player entities from polluting the enemy radar.
 *  4. Universal {@code knownAirBlocks} tracker flags ANY block placed by you OR enemies during the match
 *     as {@code isPlayerPlaced = true} on every server without fragile packet mixins.
 */
public final class ArenaLandmarkCache {

    private static final int MAX_KNOWN_AIR_CELLS = 24_000;
    private static final int SCAN_RADIUS_XZ = 44;
    private static final int SCAN_HALF_Y = 16;

    private static final class TrackedBed {
        final BlockPos pos;
        final String blockPath;
        final int colorTeamId;
        boolean alive = true;

        TrackedBed(BlockPos pos, String blockPath, int colorTeamId) {
            this.pos = pos;
            this.blockPath = blockPath;
            this.colorTeamId = colorTeamId;
        }
    }

    private static final class StaticPoi {
        final int type;
        final float x, y, z;

        StaticPoi(int type, float x, float y, float z) {
            this.type = type;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    // Deduplicated by coarse 3-block spatial bucket so two halves of a bed merge into 1 POI
    private final Map<Long, TrackedBed> bedsByBucket = new HashMap<>(16);
    private final Map<Long, StaticPoi> poisByBucket = new HashMap<>(32);
    private final List<float[]> shopNpcZones = new ArrayList<>(16);

    // Coordinates observed as AIR during the current match -> any solid block there later is 100% player-placed!
    private final Set<Long> knownAirBlocks = new HashSet<>(8192);
    private final Set<Long> inferredPlayerPlaced = new HashSet<>(4096);

    private int scanSliceOffset = -SCAN_RADIUS_XZ;

    public synchronized void reset() {
        bedsByBucket.clear();
        poisByBucket.clear();
        shopNpcZones.clear();
        knownAirBlocks.clear();
        inferredPlayerPlaced.clear();
        scanSliceOffset = -SCAN_RADIUS_XZ;
    }

    /**
     * Record a block seen during the local 13x7x13 voxel capture.
     * If a cell was previously observed as AIR in this match and is now non-air,
     * mark it as player-placed (works for BOTH your own bridges AND enemy bridges/defenses!).
     */
    public synchronized void observeLocalBlock(BlockPos pos, boolean isAir, boolean isInGame) {
        if (!isInGame) return;
        long key = pos.asLong();
        if (isAir) {
            inferredPlayerPlaced.remove(key);
            if (knownAirBlocks.size() < MAX_KNOWN_AIR_CELLS) {
                knownAirBlocks.add(key);
            }
        } else if (knownAirBlocks.contains(key)) {
            inferredPlayerPlaced.add(key);
        }
    }

    public synchronized boolean isInferredPlayerPlaced(BlockPos pos) {
        return inferredPlayerPlaced.contains(pos.asLong());
    }

    /**
     * Called once per tick during active gameplay. Scans 4 X-columns per tick (~22 ticks for full ±44 ring)
     * so we discover all beds across the arena with virtually zero CPU overhead, and verifies living beds.
     */
    public synchronized void tickScan(LocalPlayer player, ClientLevel level, boolean isInGame) {
        if (!isInGame || player == null || level == null) return;

        int px = (int) Math.floor(player.getX());
        int py = (int) Math.floor(player.getY());
        int pz = (int) Math.floor(player.getZ());
        BlockPos.MutableBlockPos mpos = new BlockPos.MutableBlockPos();

        // 1. Verify already-known beds are still intact (only if chunk is loaded)
        for (TrackedBed tb : bedsByBucket.values()) {
            if (!tb.alive) continue;
            if (level.isLoaded(tb.pos)) {
                BlockState bs = level.getBlockState(tb.pos);
                if (!(bs.getBlock() instanceof BedBlock)) {
                    tb.alive = false;
                }
            }
        }

        // 2. Scan 4 columns of the ±44 XZ ring (Y step = 1 within ±16 blocks)
        int minY = Math.max(level.getMinY(), py - SCAN_HALF_Y);
        int maxY = Math.min(level.getMaxY() - 1, py + SCAN_HALF_Y);
        for (int s = 0; s < 4; s++) {
            int dx = scanSliceOffset;
            scanSliceOffset++;
            if (scanSliceOffset > SCAN_RADIUS_XZ) {
                scanSliceOffset = -SCAN_RADIUS_XZ;
            }
            int wx = px + dx;
            // Sample every 2 blocks in Z (beds are 2 blocks long, so step=2 still hits every bed!)
            for (int dz = -SCAN_RADIUS_XZ; dz <= SCAN_RADIUS_XZ; dz += 2) {
                int wz = pz + dz;
                mpos.set(wx, py, wz);
                if (!level.isLoaded(mpos)) continue;
                for (int wy = minY; wy <= maxY; wy++) {
                    mpos.set(wx, wy, wz);
                    BlockState bs = level.getBlockState(mpos);
                    if (bs.getBlock() instanceof BedBlock) {
                        registerBed(mpos.immutable(), bs);
                    }
                }
            }
        }
    }

    public synchronized void registerBed(BlockPos pos, BlockState bs) {
        long bucket = coarseBucket(pos.getX(), pos.getY(), pos.getZ(), 4);
        TrackedBed existing = bedsByBucket.get(bucket);
        if (existing != null) {
            existing.alive = true;
            return;
        }
        String path = BuiltInRegistries.BLOCK.getKey(bs.getBlock()).getPath();
        int colorId = bedPathToTeamId(path);
        bedsByBucket.put(bucket, new TrackedBed(pos, path, colorId));
    }

    /**
     * Inspect ArmorStand holograms to auto-discover Diamond Gens, Emerald Gens,
     * Item Shopkeepers, and Team Upgrade NPCs across Pika/Jartex/Mineberry/Hypixel/Paper.
     */
    public synchronized void observeEntityHologram(Entity ent) {
        if (!(ent instanceof ArmorStand)) return;
        String raw = ent.hasCustomName() && ent.getCustomName() != null
                ? ent.getCustomName().getString()
                : ent.getName().getString();
        String clean = BedWarsStateTracker.stripFormatting(raw).toLowerCase(Locale.ROOT);
        if (clean.isEmpty() || clean.equals("armor stand")) return;

        float x = (float) ent.getX();
        float y = (float) ent.getY();
        float z = (float) ent.getZ();

        if (clean.contains("item shop") || clean.equals("shop") || clean.contains("quick buy") || clean.contains("bedwars shop")) {
            registerPoi(FifthNNSchema.LM_SHOP_NPC, x, y - 1.5f, z);
            registerShopZone(x, y - 1.5f, z);
        } else if (clean.contains("upgrade") || clean.contains("team upgrades") || clean.contains("solo upgrades")) {
            registerPoi(FifthNNSchema.LM_UPGRADE_NPC, x, y - 1.5f, z);
            registerShopZone(x, y - 1.5f, z);
        } else if (clean.contains("diamond") && (clean.contains("tier") || clean.contains("spawn") || clean.equals("diamond") || clean.contains("generator"))) {
            registerPoi(FifthNNSchema.LM_DIAMOND_GEN, x, y - 2.0f, z);
        } else if (clean.contains("emerald") && (clean.contains("tier") || clean.contains("spawn") || clean.equals("emerald") || clean.contains("generator"))) {
            registerPoi(FifthNNSchema.LM_EMERALD_GEN, x, y - 2.0f, z);
        }
    }

    public synchronized void registerShopNpcPosition(int lmType, float x, float y, float z) {
        registerPoi(lmType, x, y, z);
        registerShopZone(x, y, z);
    }

    public synchronized boolean isNearShopNpcHologram(double x, double y, double z) {
        for (float[] zone : shopNpcZones) {
            double dx = x - zone[0];
            double dy = y - zone[1];
            double dz = z - zone[2];
            if (dx * dx + dz * dz <= 2.25 && Math.abs(dy) <= 3.0) {
                return true;
            }
        }
        return false;
    }

    private void registerPoi(int type, float x, float y, float z) {
        long bucket = (coarseBucket((int) x, (int) y, (int) z, 4) << 4) | (type & 0xF);
        poisByBucket.putIfAbsent(bucket, new StaticPoi(type, x, y, z));
    }

    private void registerShopZone(float x, float y, float z) {
        for (float[] zone : shopNpcZones) {
            if (Math.hypot(x - zone[0], z - zone[2]) < 2.0 && Math.abs(y - zone[1]) < 3.0) {
                return;
            }
        }
        if (shopNpcZones.size() < 24) {
            shopNpcZones.add(new float[]{x, y, z});
        }
    }

    /**
     * Populate FrameData.landmarks with all remembered beds, generators, and shop NPCs
     * sorted closest-first so the NN always has accurate egocentric vectors across the whole map.
     */
    public synchronized void fillLandmarks(FrameData fr, int ownTeamId, double px, double py, double pz) {
        List<FrameData.LandmarkEntry> collected = new ArrayList<>(32);

        for (TrackedBed tb : bedsByBucket.values()) {
            boolean isOwn = (ownTeamId > 0 && tb.colorTeamId == ownTeamId);
            int type = isOwn ? FifthNNSchema.LM_OWN_BED : FifthNNSchema.LM_ENEMY_BED;
            collected.add(new FrameData.LandmarkEntry(
                    type,
                    tb.colorTeamId,
                    tb.pos.getX() + 0.5f,
                    tb.pos.getY() + 0.5f,
                    tb.pos.getZ() + 0.5f,
                    tb.alive ? 1 : 0
            ));
        }
        for (StaticPoi poi : poisByBucket.values()) {
            collected.add(new FrameData.LandmarkEntry(poi.type, 0, poi.x, poi.y, poi.z, 1));
        }

        collected.sort(Comparator.comparingDouble(l -> {
            double dx = l.x() - px;
            double dy = l.y() - py;
            double dz = l.z() - pz;
            return dx * dx + dy * dy + dz * dz;
        }));

        for (FrameData.LandmarkEntry e : collected) {
            if (fr.landmarks.size() >= FifthNNSchema.MAX_LANDMARKS_RAW) break;
            fr.landmarks.add(e);
        }
    }

    public synchronized int getTrackedBedCount() {
        int alive = 0;
        for (TrackedBed tb : bedsByBucket.values()) {
            if (tb.alive) alive++;
        }
        return alive;
    }

    public synchronized int getTrackedGenCount() {
        int cnt = 0;
        for (StaticPoi poi : poisByBucket.values()) {
            if (poi.type == FifthNNSchema.LM_DIAMOND_GEN || poi.type == FifthNNSchema.LM_EMERALD_GEN) cnt++;
        }
        return cnt;
    }

    public synchronized int getTrackedShopCount() {
        int cnt = 0;
        for (StaticPoi poi : poisByBucket.values()) {
            if (poi.type == FifthNNSchema.LM_SHOP_NPC || poi.type == FifthNNSchema.LM_UPGRADE_NPC) cnt++;
        }
        return cnt;
    }

    private static long coarseBucket(int x, int y, int z, int div) {
        long bx = Math.floorDiv(x, div) & 0xFFFFFL;
        long by = Math.floorDiv(y, div) & 0xFFFL;
        long bz = Math.floorDiv(z, div) & 0xFFFFFL;
        return (bx << 32) | (by << 20) | bz;
    }

    private static int bedPathToTeamId(String path) {
        if (path.startsWith("red_")) return 1;
        if (path.startsWith("blue_")) return 2;
        if (path.startsWith("green_") || path.startsWith("lime_")) return 3;
        if (path.startsWith("yellow_")) return 4;
        if (path.startsWith("cyan_") || path.startsWith("light_blue_")) return 5;
        if (path.startsWith("white_")) return 6;
        if (path.startsWith("pink_") || path.startsWith("magenta_")) return 7;
        if (path.startsWith("gray_") || path.startsWith("light_gray_")) return 8;
        return 0;
    }
}
