package io.fifthnn.recorder;

import io.fifthnn.config.FifthNNConfig;
import io.fifthnn.mixin.MultiPlayerGameModeAccessor;
import io.fifthnn.schema.FifthNNSchema;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.Locale;

/**
 * Captures the complete 1:1 visual-equivalent structured FrameData directly from Minecraft 26.3.
 *
 * v0.2 upgrades:
 *  - Finite-difference remote player velocities (fixes multiplayer zero-vel bug)
 *  - Persistent full-map ArenaLandmarkCache (beds + ArmorStand gens/shops + broken bed tracking)
 *  - Fake-player shopkeeper NPC exclusion so shop NPCs never pollute the enemy radar
 *  - Dyed leather armor RGB team detection across Pika/Jartex/Mineberry/Hypixel
 *  - Universal knownAirBlocks player-placed detection (both self and enemy bridges/defenses)
 *  - Real MultiPlayerGameMode destroyProgress mining progress capture
 */
public final class WorldSnapshotCapture {
    private WorldSnapshotCapture() {}

    public static FrameData capture(
            Minecraft mc,
            long tickIndex,
            InputTracker inputTracker,
            BedWarsStateTracker stateTracker,
            FifthNNConfig config
    ) {
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null) return null;

        // 0. Pre-capture update: resolve server profile, team color (armor RGB + scoreboard), and lobby/match phase!
        stateTracker.preCaptureUpdate(mc, player);
        boolean isInGame = stateTracker.getMatchPhase() == FifthNNSchema.MATCH_PHASE_IN_GAME;
        ArenaLandmarkCache cache = stateTracker.getLandmarkCache();
        cache.tickScan(player, level, isInGame);

        FrameData fr = new FrameData();
        fr.tickIndex = tickIndex;
        fr.timestampMs = System.currentTimeMillis();
        fr.ownTeamId = stateTracker.getOwnTeamId();

        // Ping
        if (mc.getConnection() != null) {
            PlayerInfo info = mc.getConnection().getPlayerInfo(player.getUUID());
            if (info != null) {
                fr.pingMs = (short) Math.max(0, Math.min(Short.MAX_VALUE, info.getLatency()));
            }
        }

        // 1. Self Kinematics, Vitals & 1.8 PvP Attributes
        fr.x = player.getX();
        fr.y = player.getY();
        fr.z = player.getZ();
        Vec3 vel = player.getDeltaMovement();
        fr.vx = (float) vel.x;
        fr.vy = (float) vel.y;
        fr.vz = (float) vel.z;
        fr.yaw = player.getYRot();
        fr.pitch = player.getXRot();
        fr.eyeHeight = player.getEyeHeight();
        fr.fallDist = (float) player.fallDistance;
        fr.hp = player.getHealth();
        fr.absorption = player.getAbsorptionAmount();
        fr.hunger = player.getFoodData().getFoodLevel();
        fr.swingProgress = player.getSwingAnimation(0.0f);
        fr.attackCooldown = player.getAttackStrengthScale(0.0f);
        var atkAttr = player.getAttribute(Attributes.ATTACK_SPEED);
        fr.attackSpeedAttr = atkAttr != null ? (float) atkAttr.getValue() : 4.0f;

        int sFlags = 0;
        if (player.onGround()) sFlags |= FifthNNSchema.SFLAG_ON_GROUND;
        if (player.isShiftKeyDown() || player.isCrouching()) sFlags |= FifthNNSchema.SFLAG_SNEAKING;
        if (player.isSprinting()) sFlags |= FifthNNSchema.SFLAG_SPRINTING;
        if (player.isInWater()) sFlags |= FifthNNSchema.SFLAG_IN_WATER;
        if (player.isOnFire()) sFlags |= FifthNNSchema.SFLAG_ON_FIRE;
        if (player.isSwimming()) sFlags |= FifthNNSchema.SFLAG_SWIMMING;
        if (player.isUsingItem()) sFlags |= FifthNNSchema.SFLAG_USING_ITEM;
        if (player.isFallFlying()) sFlags |= FifthNNSchema.SFLAG_FALL_FLYING;
        fr.selfFlags = sFlags;
        fr.hurtTime = player.hurtTime;

        int eff = 0;
        if (player.hasEffect(MobEffects.SPEED)) eff |= 1 << 0;
        if (player.hasEffect(MobEffects.JUMP_BOOST)) eff |= 1 << 1;
        if (player.hasEffect(MobEffects.INVISIBILITY)) eff |= 1 << 2;
        if (player.hasEffect(MobEffects.REGENERATION) || player.hasEffect(MobEffects.ABSORPTION)) eff |= 1 << 3;
        if (player.hasEffect(MobEffects.HASTE)) eff |= 1 << 4;
        if (player.hasEffect(MobEffects.MINING_FATIGUE)) eff |= 1 << 5;
        if (player.hasEffect(MobEffects.BLINDNESS)) eff |= 1 << 6;
        fr.effectFlags = eff;

        // 2. Crosshair Target (Block + real destroyProgress, or Entity)
        captureCrosshair(mc, player, level, fr, stateTracker);

        // 3. 1:1 Local 13x7x13 Voxel Cube + universal air->solid player-placed tracking
        captureLocalVoxels(player, level, fr, stateTracker, isInGame);

        // 4. 32-Ray Panoramic Lidar (16 horizontal + 8 downward ledge/void + 8 forward fan)
        capturePanoramicLidar(player, level, fr, stateTracker);

        // 5. Entities: ArmorStand holograms FIRST (identifies shop NPC zones & gens), then Players & Threats!
        captureEntities(player, level, fr, stateTracker, tickIndex, config.recordObserverEntities);

        // 6. Populate persistent ArenaLandmarkCache (all known beds, gens, shopkeepers across whole map!)
        cache.fillLandmarks(fr, fr.ownTeamId, fr.x, fr.y, fr.z);

        // 7. Full 41-Slot Inventory, Economy & Gear Tiers
        captureInventoryAndEconomy(player, fr);

        // 8. Open GUI / Shop Container Snapshot
        captureGuiState(mc, fr);

        // 9. Live Human Key States + Sub-Tick Inputs + Sound Radar
        captureKeyMask(mc, fr);
        inputTracker.drainIntoFrame(fr, fr.yaw, fr.pitch);

        // 10. BedWars Match State, Teleport Scrubbing, Quality Flags & Anti-Loophole Milestone Rewards
        stateTracker.tickAndFillFrame(mc, player, fr, config.autoTagLobbyInvalid);

        return fr;
    }

    private static void captureCrosshair(
            Minecraft mc, LocalPlayer player, ClientLevel level, FrameData fr, BedWarsStateTracker st
    ) {
        if (mc.gameMode != null) {
            fr.miningProgress = Math.max(0.0f, Math.min(1.0f,
                    ((MultiPlayerGameModeAccessor) mc.gameMode).fifthnn$getDestroyProgress()));
        }

        HitResult hr = mc.hitResult;
        if (hr == null || hr.getType() == HitResult.Type.MISS) {
            fr.hitType = 0;
            fr.hitDist = 6.0f;
            fr.hitEntityId = -1;
            return;
        }

        Vec3 eye = player.getEyePosition(1.0f);
        fr.hitDist = (float) eye.distanceTo(hr.getLocation());

        if (hr instanceof BlockHitResult bhr) {
            fr.hitType = 1;
            BlockPos bp = bhr.getBlockPos();
            fr.hitBx = bp.getX();
            fr.hitBy = bp.getY();
            fr.hitBz = bp.getZ();
            fr.hitFace = bhr.getDirection().ordinal();
            BlockState bs = level.getBlockState(bp);
            int packed = classifyPackedVoxel(bs, bp, st, fr.ownTeamId);
            fr.hitBlockCat = packed & 0x3F;
            int hFlags = 0;
            if ((packed & 0x40) != 0) hFlags |= 1 << 0; // breakable
            if ((packed & 0x80) != 0) hFlags |= 1 << 1; // playerPlaced
            if (fr.hitBlockCat == FifthNNSchema.BLOCK_BED_ENEMY) hFlags |= 1 << 2;
            if (fr.hitBlockCat == FifthNNSchema.BLOCK_BED_OWN) hFlags |= 1 << 3;
            fr.hitFlags = hFlags;
            fr.hitEntityId = -1;
        } else if (hr instanceof EntityHitResult ehr) {
            fr.hitType = 2;
            Entity ent = ehr.getEntity();
            fr.hitEntityId = ent.getId();
            int hFlags = 0;
            if (isShopNpc(ent, st.getLandmarkCache())) {
                hFlags |= 1 << 5; // shop NPC
            } else if (ent instanceof Player p && !isTeammate(player, p, fr.ownTeamId)) {
                hFlags |= 1 << 4; // enemy player
            }
            fr.hitFlags = hFlags;
        }
    }

    private static void captureLocalVoxels(
            LocalPlayer player, ClientLevel level, FrameData fr, BedWarsStateTracker st, boolean isInGame
    ) {
        int baseX = (int) Math.floor(player.getX());
        int baseY = (int) Math.floor(player.getY());
        int baseZ = (int) Math.floor(player.getZ());
        int rad = FifthNNSchema.RAW_VOXEL_RADIUS_XZ;
        BlockPos.MutableBlockPos mpos = new BlockPos.MutableBlockPos();
        ArenaLandmarkCache cache = st.getLandmarkCache();

        int idx = 0;
        for (int dy = FifthNNSchema.RAW_VOXEL_MIN_Y; dy <= FifthNNSchema.RAW_VOXEL_MAX_Y; dy++) {
            for (int dz = -rad; dz <= rad; dz++) {
                for (int dx = -rad; dx <= rad; dx++) {
                    mpos.set(baseX + dx, baseY + dy, baseZ + dz);
                    BlockState bs = level.getBlockState(mpos);
                    cache.observeLocalBlock(mpos, bs.isAir(), isInGame);
                    if (bs.getBlock() instanceof BedBlock) {
                        cache.registerBed(mpos.immutable(), bs);
                    }
                    int packed = classifyPackedVoxel(bs, mpos, st, fr.ownTeamId);
                    fr.voxels[idx++] = (byte) packed;
                }
            }
        }
    }

    private static void capturePanoramicLidar(LocalPlayer player, ClientLevel level, FrameData fr, BedWarsStateTracker st) {
        Vec3 eye = player.getEyePosition(1.0f);
        Vec3 feet = new Vec3(player.getX(), player.getY() + 0.25, player.getZ());
        float yaw = player.getYRot();

        // 0..15: 16 horizontal rays around player yaw (max 32 blocks)
        for (int i = 0; i < 16; i++) {
            double ang = Math.toRadians(yaw + i * 22.5);
            Vec3 dir = new Vec3(-Math.sin(ang), 0.0, Math.cos(ang));
            castRay(level, eye, dir, 32.0, i, fr, st);
        }

        // 16..23: 8 downward ledge/void detection rays at -45 deg pitch (max 16 blocks)
        for (int i = 0; i < 8; i++) {
            double ang = Math.toRadians(yaw + i * 45.0);
            double horiz = Math.cos(Math.toRadians(45.0));
            double vert = -Math.sin(Math.toRadians(45.0));
            Vec3 dir = new Vec3(-Math.sin(ang) * horiz, vert, Math.cos(ang) * horiz);
            castRay(level, feet, dir, 16.0, 16 + i, fr, st);
        }

        // 24..31: 8 forward-fan rays (symmetric -/+ yaw pairs for exact mirror augmentation)
        float[] fanYaws = {-15f, 15f, -30f, 30f, -15f, 15f, -30f, 30f};
        float[] fanPitches = {-15f, -15f, -15f, -15f, 18f, 18f, 18f, 18f};
        for (int i = 0; i < 8; i++) {
            double yRad = Math.toRadians(yaw + fanYaws[i]);
            double pRad = Math.toRadians(fanPitches[i]);
            double cp = Math.cos(pRad);
            Vec3 dir = new Vec3(-Math.sin(yRad) * cp, -Math.sin(pRad), Math.cos(yRad) * cp);
            castRay(level, eye, dir, 32.0, 24 + i, fr, st);
        }
    }

    private static void castRay(
            ClientLevel level, Vec3 origin, Vec3 dir, double maxDist, int rayIdx, FrameData fr, BedWarsStateTracker st
    ) {
        Vec3 end = origin.add(dir.scale(maxDist));
        BlockHitResult bhr = level.clip(new ClipContext(
                origin, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
                net.minecraft.world.phys.shapes.CollisionContext.empty()));
        if (bhr.getType() == HitResult.Type.MISS) {
            fr.lidarDist[rayIdx] = (float) maxDist;
            fr.lidarCat[rayIdx] = FifthNNSchema.BLOCK_AIR;
            fr.lidarFlags[rayIdx] = (byte) ((rayIdx >= 16 && rayIdx < 24) ? 0x04 : 0x00);
        } else {
            double dist = origin.distanceTo(bhr.getLocation());
            BlockPos bp = bhr.getBlockPos();
            int packed = classifyPackedVoxel(level.getBlockState(bp), bp, st, fr.ownTeamId);
            fr.lidarDist[rayIdx] = (float) dist;
            fr.lidarCat[rayIdx] = (byte) (packed & 0x3F);
            int lflags = 0;
            if ((packed & 0x40) != 0) lflags |= 0x01; // breakable
            if ((packed & 0x80) != 0) lflags |= 0x02; // playerPlaced
            if (rayIdx >= 16 && rayIdx < 24 && dist > 6.5) lflags |= 0x04; // deep drop / void
            fr.lidarFlags[rayIdx] = (byte) lflags;
        }
    }

    private static void captureEntities(
            LocalPlayer self,
            ClientLevel level,
            FrameData fr,
            BedWarsStateTracker stateTracker,
            long tickIndex,
            boolean includeObservers
    ) {
        ArenaLandmarkCache cache = stateTracker.getLandmarkCache();
        EntityVelocityTracker velTracker = stateTracker.getVelocityTracker();

        // Pass 1: Scan ArmorStands first so shopkeeper hologram zones are known before checking Players!
        for (Entity ent : level.entitiesForRendering()) {
            if (ent instanceof ArmorStand && ent.isAlive() && ent.distanceToSqr(self) <= 64.0 * 64.0) {
                cache.observeEntityHologram(ent);
            }
        }

        // Pass 2: Players, Shop NPCs, Threats, and Dropped BedWars Resources
        for (Entity ent : level.entitiesForRendering()) {
            if (ent == self || !ent.isAlive() || ent instanceof ArmorStand) continue;
            double distSq = ent.distanceToSqr(self);
            if (distSq > 64.0 * 64.0) continue;

            // Shop / Upgrade NPCs (Villagers, Traders, Blazes, Witches, OR fake-player NPCs under shop holograms!)
            if (isShopNpc(ent, cache)) {
                String name = BedWarsStateTracker.stripFormatting(ent.getName().getString()).toLowerCase(Locale.ROOT);
                int lType = name.contains("upgrade") ? FifthNNSchema.LM_UPGRADE_NPC : FifthNNSchema.LM_SHOP_NPC;
                cache.registerShopNpcPosition(lType, (float) ent.getX(), (float) ent.getY(), (float) ent.getZ());
                continue;
            }

            // Real Players (opponents & teammates)
            if (ent instanceof Player p && !p.isSpectator() && includeObservers) {
                int enemyTeamId = BedWarsStateTracker.inferTeamIdFromArmor(p);
                if (enemyTeamId == 0 && p.getTeam() != null) {
                    enemyTeamId = BedWarsStateTracker.teamColorToTeamId(p.getTeam().getColor());
                }

                int pFlags = 0;
                if (isTeammate(self, p, fr.ownTeamId, enemyTeamId)) pFlags |= 1 << 0;
                if (p.onGround()) pFlags |= 1 << 1;
                if (p.isCrouching()) pFlags |= 1 << 2;
                if (p.isSprinting()) pFlags |= 1 << 3;
                if (p.isInvisible()) pFlags |= 1 << 4;
                if (p.isSwinging()) pFlags |= 1 << 5;
                if (p.hurtTime > 0) pFlags |= 1 << 6;

                float[] pv = velTracker.computeVelocity(p, tickIndex);
                int mainCat = classifyItem(p.getMainHandItem());
                int offCat = classifyItem(p.getOffhandItem());
                int armorSum = computeArmorSum(p);

                fr.players.add(new FrameData.PlayerEntry(
                        p.getId(),
                        p.getUUID().hashCode(),
                        enemyTeamId,
                        pFlags,
                        (float) p.getX(), (float) p.getY(), (float) p.getZ(),
                        pv[0], pv[1], pv[2],
                        p.getYRot(), p.getXRot(),
                        p.getHealth(),
                        mainCat, offCat, armorSum
                ));
                continue;
            }

            // Threats & Dropped BedWars Resources within 32 blocks
            if (distSq <= 32.0 * 32.0) {
                int tType = 0;
                float extra = 1.0f;
                if (ent instanceof PrimedTnt tnt) {
                    tType = FifthNNSchema.THREAT_TNT;
                    extra = tnt.getFuse();
                } else if (ent instanceof Projectile) {
                    String id = BuiltInRegistries.ENTITY_TYPE.getKey(ent.getType()).getPath();
                    if (id.contains("fireball")) tType = FifthNNSchema.THREAT_FIREBALL;
                    else if (id.contains("arrow")) tType = FifthNNSchema.THREAT_ARROW;
                    else if (id.contains("ender_pearl")) tType = FifthNNSchema.THREAT_ENDER_PEARL;
                    else if (id.contains("egg")) tType = FifthNNSchema.THREAT_EGG;
                } else if (ent instanceof ItemEntity ie) {
                    int icat = classifyItem(ie.getItem());
                    extra = ie.getItem().getCount();
                    if (icat == FifthNNSchema.ITEM_IRON_INGOT) tType = FifthNNSchema.THREAT_DROP_IRON;
                    else if (icat == FifthNNSchema.ITEM_GOLD_INGOT) tType = FifthNNSchema.THREAT_DROP_GOLD;
                    else if (icat == FifthNNSchema.ITEM_DIAMOND) tType = FifthNNSchema.THREAT_DROP_DIAMOND;
                    else if (icat == FifthNNSchema.ITEM_EMERALD) tType = FifthNNSchema.THREAT_DROP_EMERALD;
                    else tType = FifthNNSchema.THREAT_DROP_OTHER;
                } else if (ent instanceof LivingEntity) {
                    String id = BuiltInRegistries.ENTITY_TYPE.getKey(ent.getType()).getPath();
                    if (id.contains("silverfish") || id.contains("iron_golem")) {
                        tType = FifthNNSchema.THREAT_MOB;
                        extra = ((LivingEntity) ent).getHealth();
                    }
                }

                if (tType > 0) {
                    float[] tv = velTracker.computeVelocity(ent, tickIndex);
                    fr.threats.add(new FrameData.ThreatEntry(
                            tType,
                            (float) ent.getX(), (float) ent.getY(), (float) ent.getZ(),
                            tv[0], tv[1], tv[2],
                            extra
                    ));
                }
            }
        }

        fr.players.sort(Comparator.comparingDouble(p ->
                sq(p.x() - self.getX()) + sq(p.y() - self.getY()) + sq(p.z() - self.getZ())));
        if (fr.players.size() > FifthNNSchema.MAX_PLAYERS_RAW) {
            fr.players.subList(FifthNNSchema.MAX_PLAYERS_RAW, fr.players.size()).clear();
        }
        fr.threats.sort(Comparator.comparingDouble(t ->
                sq(t.x() - self.getX()) + sq(t.y() - self.getY()) + sq(t.z() - self.getZ())));
        if (fr.threats.size() > FifthNNSchema.MAX_THREATS_RAW) {
            fr.threats.subList(FifthNNSchema.MAX_THREATS_RAW, fr.threats.size()).clear();
        }
    }

    private static void captureInventoryAndEconomy(LocalPlayer player, FrameData fr) {
        Inventory inv = player.getInventory();
        fr.selectedSlot = inv.getSelectedSlot();

        int iron = 0, gold = 0, diamond = 0, emerald = 0;
        int blocks = 0, gapples = 0, fireballs = 0, tnt = 0, pearls = 0, potions = 0;
        int swordTier = 0, pickTier = 0, axeTier = 0, shears = 0, bow = 0;

        for (int i = 0; i < 36; i++) {
            ItemStack st = inv.getItem(i);
            int cat = classifyItem(st);
            fr.invItemCat[i] = (byte) cat;
            fr.invCount[i] = (byte) Math.min(255, st.getCount());
            fr.invEnchantFlags[i] = (byte) (st.isEnchanted() ? 1 : 0);

            int cnt = st.getCount();
            switch (cat) {
                case FifthNNSchema.ITEM_IRON_INGOT -> iron += cnt;
                case FifthNNSchema.ITEM_GOLD_INGOT -> gold += cnt;
                case FifthNNSchema.ITEM_DIAMOND -> diamond += cnt;
                case FifthNNSchema.ITEM_EMERALD -> emerald += cnt;
                case FifthNNSchema.ITEM_WOOL, FifthNNSchema.ITEM_WOOD_PLANKS, FifthNNSchema.ITEM_ENDSTONE,
                     FifthNNSchema.ITEM_TERRACOTTA, FifthNNSchema.ITEM_BLASTPROOF_GLASS, FifthNNSchema.ITEM_OBSIDIAN -> blocks += cnt;
                case FifthNNSchema.ITEM_GOLDEN_APPLE -> gapples += cnt;
                case FifthNNSchema.ITEM_FIREBALL -> fireballs += cnt;
                case FifthNNSchema.ITEM_TNT -> tnt += cnt;
                case FifthNNSchema.ITEM_ENDER_PEARL -> pearls += cnt;
                case FifthNNSchema.ITEM_POTION_SPEED, FifthNNSchema.ITEM_POTION_JUMP, FifthNNSchema.ITEM_POTION_INVIS -> potions += cnt;
                case FifthNNSchema.ITEM_WOOD_SWORD -> swordTier = Math.max(swordTier, 1);
                case FifthNNSchema.ITEM_STONE_SWORD -> swordTier = Math.max(swordTier, 2);
                case FifthNNSchema.ITEM_IRON_SWORD -> swordTier = Math.max(swordTier, 3);
                case FifthNNSchema.ITEM_DIAMOND_SWORD -> swordTier = Math.max(swordTier, 4);
                case FifthNNSchema.ITEM_WOOD_PICKAXE -> pickTier = Math.max(pickTier, 1);
                case FifthNNSchema.ITEM_IRON_PICKAXE -> pickTier = Math.max(pickTier, 2);
                case FifthNNSchema.ITEM_GOLD_PICKAXE -> pickTier = Math.max(pickTier, 3);
                case FifthNNSchema.ITEM_DIAMOND_PICKAXE -> pickTier = Math.max(pickTier, 4);
                case FifthNNSchema.ITEM_WOOD_AXE -> axeTier = Math.max(axeTier, 1);
                case FifthNNSchema.ITEM_STONE_AXE -> axeTier = Math.max(axeTier, 2);
                case FifthNNSchema.ITEM_IRON_AXE -> axeTier = Math.max(axeTier, 3);
                case FifthNNSchema.ITEM_DIAMOND_AXE -> axeTier = Math.max(axeTier, 4);
                case FifthNNSchema.ITEM_SHEARS -> shears = 1;
                case FifthNNSchema.ITEM_BOW -> bow = 1;
                default -> {}
            }
        }

        // Armor slots (36..39) + offhand (40)
        EquipmentSlot[] armorSlots = {EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD};
        int armorTier = 0;
        for (int a = 0; a < 4; a++) {
            ItemStack st = player.getItemBySlot(armorSlots[a]);
            int cat = classifyItem(st);
            fr.invItemCat[36 + a] = (byte) cat;
            fr.invCount[36 + a] = (byte) Math.min(255, st.getCount());
            fr.invEnchantFlags[36 + a] = (byte) (st.isEnchanted() ? 1 : 0);
            if (cat == FifthNNSchema.ITEM_LEATHER_ARMOR) armorTier = Math.max(armorTier, 1);
            else if (cat == FifthNNSchema.ITEM_CHAIN_ARMOR) armorTier = Math.max(armorTier, 2);
            else if (cat == FifthNNSchema.ITEM_IRON_ARMOR) armorTier = Math.max(armorTier, 3);
            else if (cat == FifthNNSchema.ITEM_DIAMOND_ARMOR) armorTier = Math.max(armorTier, 4);
        }
        ItemStack offhand = player.getOffhandItem();
        fr.invItemCat[40] = (byte) classifyItem(offhand);
        fr.invCount[40] = (byte) Math.min(255, offhand.getCount());
        fr.invEnchantFlags[40] = (byte) (offhand.isEnchanted() ? 1 : 0);

        fr.econ[0] = (short) Math.min(Short.MAX_VALUE, iron);
        fr.econ[1] = (short) Math.min(Short.MAX_VALUE, gold);
        fr.econ[2] = (short) Math.min(Short.MAX_VALUE, diamond);
        fr.econ[3] = (short) Math.min(Short.MAX_VALUE, emerald);
        fr.econ[4] = (short) Math.min(Short.MAX_VALUE, blocks);
        fr.econ[5] = (short) Math.min(Short.MAX_VALUE, gapples);
        fr.econ[6] = (short) Math.min(Short.MAX_VALUE, fireballs);
        fr.econ[7] = (short) Math.min(Short.MAX_VALUE, tnt);
        fr.econ[8] = (short) Math.min(Short.MAX_VALUE, pearls);
        fr.econ[9] = (short) Math.min(Short.MAX_VALUE, potions);

        fr.gearTiers[0] = (byte) swordTier;
        fr.gearTiers[1] = (byte) armorTier;
        fr.gearTiers[2] = (byte) pickTier;
        fr.gearTiers[3] = (byte) axeTier;
        fr.gearTiers[4] = (byte) shears;
        fr.gearTiers[5] = (byte) bow;

        // 16-bit canonical BedWars affordability mask
        int aff = 0;
        if (iron >= 4) aff |= 1 << 0;
        if (iron >= 10) aff |= 1 << 1;
        if (iron >= 20) aff |= 1 << 2;
        if (iron >= 24) aff |= 1 << 3;
        if (iron >= 40) aff |= 1 << 4;
        if (gold >= 3) aff |= 1 << 5;
        if (gold >= 4) aff |= 1 << 6;
        if (gold >= 7) aff |= 1 << 7;
        if (gold >= 12) aff |= 1 << 8;
        if (diamond >= 2) aff |= 1 << 9;
        if (diamond >= 4) aff |= 1 << 10;
        if (diamond >= 8) aff |= 1 << 11;
        if (emerald >= 1) aff |= 1 << 12;
        if (emerald >= 2) aff |= 1 << 13;
        if (emerald >= 4) aff |= 1 << 14;
        if (emerald >= 6) aff |= 1 << 15;
        fr.affordableMask = aff;
    }

    private static void captureGuiState(Minecraft mc, FrameData fr) {
        Screen screen = mc.gui.screen();
        if (screen == null) {
            fr.guiType = FifthNNSchema.GUI_NONE;
            fr.guiSlotCount = 0;
            return;
        }
        if (screen instanceof PauseScreen) {
            fr.guiType = FifthNNSchema.GUI_PAUSE;
            fr.guiSlotCount = 0;
            return;
        }
        if (screen instanceof ChatScreen) {
            fr.guiType = FifthNNSchema.GUI_CHAT;
            fr.guiSlotCount = 0;
            return;
        }
        if (screen instanceof InventoryScreen) {
            fr.guiType = FifthNNSchema.GUI_PLAYER_INV;
        } else if (screen instanceof AbstractContainerScreen<?> acs) {
            String title = BedWarsStateTracker.stripFormatting(acs.getTitle().getString()).toLowerCase(Locale.ROOT);
            if (title.contains("upgrade") || title.contains("trap")) {
                fr.guiType = FifthNNSchema.GUI_UPGRADE_SHOP;
            } else if (title.contains("shop") || title.contains("quick buy") || title.contains("blocks")
                    || title.contains("melee") || title.contains("armor") || title.contains("tools")
                    || title.contains("utility") || title.contains("potions") || title.contains("ranged")) {
                fr.guiType = FifthNNSchema.GUI_ITEM_SHOP;
                fr.shopTab = detectShopTab(title);
            } else if (title.contains("ender chest")) {
                fr.guiType = FifthNNSchema.GUI_ENDER_CHEST;
            } else if (title.contains("chest")) {
                fr.guiType = FifthNNSchema.GUI_TEAM_CHEST;
            } else {
                fr.guiType = FifthNNSchema.GUI_OTHER;
            }

            AbstractContainerMenu menu = acs.getMenu();
            int n = Math.min(menu.slots.size(), FifthNNSchema.MAX_GUI_SLOTS);
            fr.guiSlotCount = n;
            for (int i = 0; i < n; i++) {
                Slot slot = menu.slots.get(i);
                ItemStack st = slot.getItem();
                fr.guiItemCat[i] = (byte) classifyItem(st);
                fr.guiCount[i] = (byte) Math.min(255, st.getCount());
                int flags = 0;
                if (!st.isEmpty()) flags |= 1 << 0;
                if (st.isEnchanted()) flags |= 1 << 1;
                fr.guiFlags[i] = (byte) flags;
            }
            return;
        } else {
            fr.guiType = FifthNNSchema.GUI_OTHER;
        }
        fr.guiSlotCount = 0;
    }

    private static int detectShopTab(String title) {
        if (title.contains("quick")) return 0;
        if (title.contains("block")) return 1;
        if (title.contains("melee") || title.contains("weapon")) return 2;
        if (title.contains("armor")) return 3;
        if (title.contains("tool")) return 4;
        if (title.contains("ranged") || title.contains("bow")) return 5;
        if (title.contains("potion")) return 6;
        if (title.contains("util")) return 7;
        return 0;
    }

    private static void captureKeyMask(Minecraft mc, FrameData fr) {
        var o = mc.options;
        int km = 0;
        if (o.keyUp.isDown()) km |= FifthNNSchema.KEY_W;
        if (o.keyLeft.isDown()) km |= FifthNNSchema.KEY_A;
        if (o.keyDown.isDown()) km |= FifthNNSchema.KEY_S;
        if (o.keyRight.isDown()) km |= FifthNNSchema.KEY_D;
        if (o.keyJump.isDown()) km |= FifthNNSchema.KEY_JUMP;
        if (o.keyShift.isDown()) km |= FifthNNSchema.KEY_SNEAK;
        if (o.keySprint.isDown()) km |= FifthNNSchema.KEY_SPRINT;
        if (o.keyDrop.isDown()) km |= FifthNNSchema.KEY_DROP;
        if (o.keySwapOffhand.isDown()) km |= FifthNNSchema.KEY_SWAP_OFFHAND;
        if (o.keyInventory.isDown()) km |= FifthNNSchema.KEY_OPEN_INV;
        if (o.keyAttack.isDown()) km |= FifthNNSchema.KEY_LEFT_HELD;
        if (o.keyUse.isDown()) km |= FifthNNSchema.KEY_RIGHT_HELD;
        fr.keyMask = km;
    }

    public static int classifyPackedVoxel(BlockState bs, BlockPos pos, BedWarsStateTracker st, int ownTeamId) {
        if (bs.isAir()) return FifthNNSchema.BLOCK_AIR;
        Block b = bs.getBlock();
        String path = BuiltInRegistries.BLOCK.getKey(b).getPath();

        boolean trackedPlaced = st.isTrackedPlayerPlaced(pos);
        boolean breakable = trackedPlaced;
        int cat;

        if (b instanceof BedBlock) {
            cat = FifthNNSchema.BLOCK_BED_ENEMY;
            breakable = true;
            if (ownTeamId > 0 && matchesTeamBedColor(path, ownTeamId)) {
                cat = FifthNNSchema.BLOCK_BED_OWN;
                breakable = false;
            }
        } else if (path.endsWith("_wool")) {
            cat = FifthNNSchema.BLOCK_WOOL;
            breakable = true;
        } else if (path.endsWith("_planks")) {
            cat = FifthNNSchema.BLOCK_WOOD_PLANKS;
            breakable = true;
        } else if (b == Blocks.END_STONE) {
            cat = FifthNNSchema.BLOCK_ENDSTONE;
            breakable = true;
        } else if (path.contains("terracotta")) {
            cat = FifthNNSchema.BLOCK_TERRACOTTA;
            breakable = true;
        } else if (path.contains("glass")) {
            cat = FifthNNSchema.BLOCK_BLASTPROOF_GLASS;
            breakable = trackedPlaced || path.contains("stained_glass");
        } else if (b == Blocks.OBSIDIAN) {
            cat = FifthNNSchema.BLOCK_OBSIDIAN;
            breakable = true;
        } else if (b == Blocks.LADDER || b == Blocks.VINE) {
            cat = FifthNNSchema.BLOCK_LADDER_VINE;
            breakable = true;
        } else if (b == Blocks.WATER) {
            cat = FifthNNSchema.BLOCK_WATER;
        } else if (b == Blocks.LAVA || b == Blocks.FIRE) {
            cat = FifthNNSchema.BLOCK_LAVA_FIRE;
        } else if (b == Blocks.CHEST || b == Blocks.ENDER_CHEST || b == Blocks.TRAPPED_CHEST) {
            cat = FifthNNSchema.BLOCK_CHEST_ENDERCHEST;
        } else if (b == Blocks.DIAMOND_BLOCK || b == Blocks.EMERALD_BLOCK || b == Blocks.IRON_BLOCK || b == Blocks.GOLD_BLOCK) {
            cat = FifthNNSchema.BLOCK_GENERATOR;
        } else if (b == Blocks.COBWEB || b == Blocks.SLIME_BLOCK || b == Blocks.SPONGE || b == Blocks.WET_SPONGE) {
            cat = FifthNNSchema.BLOCK_COBWEB_SLIME;
            breakable = true;
        } else if (path.contains("slab") || path.contains("stairs") || path.contains("fence") || path.contains("wall")) {
            cat = FifthNNSchema.BLOCK_SLAB_STAIR_FENCE;
        } else {
            cat = trackedPlaced ? FifthNNSchema.BLOCK_SOLID_BREAKABLE_OTHER : FifthNNSchema.BLOCK_SOLID_MAP;
        }

        int packed = cat & 0x3F;
        if (breakable) packed |= 0x40;
        if (trackedPlaced) packed |= 0x80;
        return packed;
    }

    private static boolean matchesTeamBedColor(String path, int teamId) {
        return switch (teamId) {
            case 1 -> path.startsWith("red_");
            case 2 -> path.startsWith("blue_");
            case 3 -> path.startsWith("green_") || path.startsWith("lime_");
            case 4 -> path.startsWith("yellow_");
            case 5 -> path.startsWith("cyan_") || path.startsWith("light_blue_");
            case 6 -> path.startsWith("white_");
            case 7 -> path.startsWith("pink_") || path.startsWith("magenta_");
            case 8 -> path.startsWith("gray_") || path.startsWith("light_gray_");
            default -> false;
        };
    }

    public static int classifyItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return FifthNNSchema.ITEM_EMPTY;
        Item item = stack.getItem();
        String path = BuiltInRegistries.ITEM.getKey(item).getPath();

        if (path.endsWith("_wool")) return FifthNNSchema.ITEM_WOOL;
        if (path.endsWith("_planks")) return FifthNNSchema.ITEM_WOOD_PLANKS;
        if (path.equals("end_stone")) return FifthNNSchema.ITEM_ENDSTONE;
        if (path.contains("terracotta")) return FifthNNSchema.ITEM_TERRACOTTA;
        if (path.contains("glass")) return FifthNNSchema.ITEM_BLASTPROOF_GLASS;
        if (path.equals("obsidian")) return FifthNNSchema.ITEM_OBSIDIAN;
        if (path.equals("ladder")) return FifthNNSchema.ITEM_LADDER;
        if (path.equals("wooden_sword")) return FifthNNSchema.ITEM_WOOD_SWORD;
        if (path.equals("stone_sword")) return FifthNNSchema.ITEM_STONE_SWORD;
        if (path.equals("iron_sword")) return FifthNNSchema.ITEM_IRON_SWORD;
        if (path.equals("diamond_sword") || path.equals("netherite_sword")) return FifthNNSchema.ITEM_DIAMOND_SWORD;
        if (path.equals("stick")) return FifthNNSchema.ITEM_KB_STICK;
        if (path.equals("wooden_pickaxe")) return FifthNNSchema.ITEM_WOOD_PICKAXE;
        if (path.equals("iron_pickaxe") || path.equals("stone_pickaxe")) return FifthNNSchema.ITEM_IRON_PICKAXE;
        if (path.equals("golden_pickaxe")) return FifthNNSchema.ITEM_GOLD_PICKAXE;
        if (path.equals("diamond_pickaxe") || path.equals("netherite_pickaxe")) return FifthNNSchema.ITEM_DIAMOND_PICKAXE;
        if (path.equals("wooden_axe")) return FifthNNSchema.ITEM_WOOD_AXE;
        if (path.equals("stone_axe")) return FifthNNSchema.ITEM_STONE_AXE;
        if (path.equals("iron_axe")) return FifthNNSchema.ITEM_IRON_AXE;
        if (path.equals("diamond_axe") || path.equals("netherite_axe")) return FifthNNSchema.ITEM_DIAMOND_AXE;
        if (path.equals("shears")) return FifthNNSchema.ITEM_SHEARS;
        if (path.equals("bow")) return FifthNNSchema.ITEM_BOW;
        if (path.equals("arrow")) return FifthNNSchema.ITEM_ARROW;
        if (path.equals("iron_ingot")) return FifthNNSchema.ITEM_IRON_INGOT;
        if (path.equals("gold_ingot")) return FifthNNSchema.ITEM_GOLD_INGOT;
        if (path.equals("diamond")) return FifthNNSchema.ITEM_DIAMOND;
        if (path.equals("emerald")) return FifthNNSchema.ITEM_EMERALD;
        if (path.contains("golden_apple")) return FifthNNSchema.ITEM_GOLDEN_APPLE;
        if (path.equals("fire_charge")) return FifthNNSchema.ITEM_FIREBALL;
        if (path.equals("tnt")) return FifthNNSchema.ITEM_TNT;
        if (path.equals("ender_pearl")) return FifthNNSchema.ITEM_ENDER_PEARL;
        if (path.equals("water_bucket")) return FifthNNSchema.ITEM_WATER_BUCKET;
        if (path.equals("egg")) return FifthNNSchema.ITEM_BRIDGE_EGG;
        if (path.equals("chest")) return FifthNNSchema.ITEM_POPUP_TOWER;
        if (path.contains("sponge")) return FifthNNSchema.ITEM_SPONGE;
        if (path.equals("snowball")) return FifthNNSchema.ITEM_SILVERFISH_EGG;
        if (path.contains("spawn_egg")) return FifthNNSchema.ITEM_IRON_GOLEM_EGG;
        if (path.contains("potion")) {
            String name = stack.getHoverName().getString().toLowerCase(Locale.ROOT);
            if (name.contains("invis")) return FifthNNSchema.ITEM_POTION_INVIS;
            if (name.contains("jump") || name.contains("leap")) return FifthNNSchema.ITEM_POTION_JUMP;
            return FifthNNSchema.ITEM_POTION_SPEED;
        }
        if (path.equals("compass")) return FifthNNSchema.ITEM_COMPASS;
        if (path.startsWith("leather_")) return FifthNNSchema.ITEM_LEATHER_ARMOR;
        if (path.startsWith("chainmail_")) return FifthNNSchema.ITEM_CHAIN_ARMOR;
        if (path.startsWith("iron_")) return FifthNNSchema.ITEM_IRON_ARMOR;
        if (path.startsWith("diamond_") || path.startsWith("netherite_")) return FifthNNSchema.ITEM_DIAMOND_ARMOR;
        return FifthNNSchema.ITEM_OTHER_ITEM;
    }

    private static boolean isTeammate(LocalPlayer self, Player other, int ownTeamId) {
        int otherTeam = BedWarsStateTracker.inferTeamIdFromArmor(other);
        return isTeammate(self, other, ownTeamId, otherTeam);
    }

    private static boolean isTeammate(LocalPlayer self, Player other, int ownTeamId, int otherTeamId) {
        if (ownTeamId > 0 && otherTeamId > 0) {
            return ownTeamId == otherTeamId;
        }
        return self.getTeam() != null && self.getTeam().equals(other.getTeam());
    }

    private static boolean isShopNpc(Entity ent, ArenaLandmarkCache cache) {
        if (ent instanceof Villager) return true;
        String typeId = BuiltInRegistries.ENTITY_TYPE.getKey(ent.getType()).getPath();
        if (typeId.equals("wandering_trader") || typeId.equals("blaze") || typeId.equals("witch") || typeId.equals("pillager")) {
            return true;
        }
        if (ent instanceof Player p) {
            if (cache != null && cache.isNearShopNpcHologram(p.getX(), p.getY(), p.getZ())) {
                return true;
            }
            String n = BedWarsStateTracker.stripFormatting(p.getName().getString()).toLowerCase(Locale.ROOT);
            return n.contains("shop") || n.contains("upgrade") || n.contains("cit-") || n.contains(" ") || n.startsWith("[");
        }
        return false;
    }

    private static int computeArmorSum(Player p) {
        int sum = 0;
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD}) {
            int cat = classifyItem(p.getItemBySlot(slot));
            if (cat == FifthNNSchema.ITEM_LEATHER_ARMOR) sum += 1;
            else if (cat == FifthNNSchema.ITEM_CHAIN_ARMOR) sum += 2;
            else if (cat == FifthNNSchema.ITEM_IRON_ARMOR) sum += 3;
            else if (cat == FifthNNSchema.ITEM_DIAMOND_ARMOR) sum += 4;
        }
        return sum;
    }

    private static double sq(double v) {
        return v * v;
    }
}
