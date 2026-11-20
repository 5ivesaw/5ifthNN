package io.fifthnn.recorder;

import io.fifthnn.schema.FifthNNSchema;

import java.util.ArrayList;
import java.util.List;

/**
 * Lossless 1:1 visual-equivalent structured snapshot of one Minecraft tick (20 Hz).
 * Serialized by BinaryDatasetWriter into .5nn.gz and directly consumed by FeatureEncoder
 * during live Phase 2 ONNX inference so training and inference share 100% identical data!
 */
public final class FrameData {
    // Frame Meta
    public long tickIndex;
    public long timestampMs;
    public int matchPhase;
    public int qualityFlags;
    public short pingMs;
    public float serverTpsEst = 20.0f;

    // Self state
    public double x, y, z;
    public float vx, vy, vz;
    public float yaw, pitch, prevYaw, prevPitch;
    public float eyeHeight;
    public float fallDist;
    public float hp, absorption, hunger;
    public float swingProgress, attackCooldown, attackSpeedAttr;
    public int selfFlags;
    public int hurtTime;
    public int ownTeamId;
    public int effectFlags;

    // Crosshair target
    public int hitType; // 0=miss, 1=block, 2=entity
    public float hitDist;
    public int hitBx, hitBy, hitBz;
    public int hitFace;
    public int hitBlockCat;
    public int hitFlags; // bit0=breakable, bit1=playerPlaced, bit2=enemyBed, bit3=ownBed, bit4=enemyPlayer, bit5=shopNpc
    public float miningProgress;
    public int hitEntityId;

    // 1:1 Local Voxel Cube (13 x 7 x 13 = 1,183 bytes)
    public final byte[] voxels = new byte[FifthNNSchema.RAW_VOXEL_COUNT];

    // 32-ray Panoramic Lidar
    public final float[] lidarDist = new float[FifthNNSchema.LIDAR_RAY_COUNT];
    public final byte[] lidarCat = new byte[FifthNNSchema.LIDAR_RAY_COUNT];
    public final byte[] lidarFlags = new byte[FifthNNSchema.LIDAR_RAY_COUNT];

    // Dynamic world lists
    public final List<LandmarkEntry> landmarks = new ArrayList<>(FifthNNSchema.MAX_LANDMARKS_RAW);
    public final List<PlayerEntry> players = new ArrayList<>(FifthNNSchema.MAX_PLAYERS_RAW);
    public final List<ThreatEntry> threats = new ArrayList<>(FifthNNSchema.MAX_THREATS_RAW);

    // Economy, gear & inventory (41 slots)
    // econ[0..9]: iron, gold, diamond, emerald, totalBlocks, gapples, fireballs, tnt, pearls, potions
    public final short[] econ = new short[10];
    public int selectedSlot;
    // gearTiers[0..5]: sword, armor, pick, axe, shears, bow
    public final byte[] gearTiers = new byte[6];
    public final byte[] invItemCat = new byte[FifthNNSchema.INVENTORY_SLOT_COUNT];
    public final byte[] invCount = new byte[FifthNNSchema.INVENTORY_SLOT_COUNT];
    public final byte[] invEnchantFlags = new byte[FifthNNSchema.INVENTORY_SLOT_COUNT];

    // GUI / Container state
    public int guiType;
    public int shopTab;
    public int affordableMask;
    public int guiSlotCount;
    public final byte[] guiItemCat = new byte[FifthNNSchema.MAX_GUI_SLOTS];
    public final byte[] guiCount = new byte[FifthNNSchema.MAX_GUI_SLOTS];
    public final byte[] guiFlags = new byte[FifthNNSchema.MAX_GUI_SLOTS];

    // Match & scoreboard state
    public int ownBedAlive = 1;
    public int enemyBedsAliveMask;
    public int enemiesAliveCount;
    public final byte[] teamUpgrades = new byte[5]; // sharp, prot, haste, forge, trap
    public int diamondTier = 1;
    public int emeraldTier = 1;
    public int matchElapsedTicks;
    public int eventFlagsThisTick;

    // Sound radar
    public final List<SoundEntry> sounds = new ArrayList<>(FifthNNSchema.MAX_SOUNDS_RAW);

    // Exact human inputs & shaped reward
    public int keyMask;
    public float rawMouseDx, rawMouseDy;
    public float deltaYaw, deltaPitch;
    public int leftClicksThisTick;
    public int rightClicksThisTick;
    public byte hotbarKeyPressed = -1;
    public byte guiClickSlot = -1; // -1=none, -2=close_gui, 0..53=slot
    public int guiClickButton;
    public int guiClickType;
    public float rewardDelta;

    public record LandmarkEntry(int type, int teamId, float x, float y, float z, int aliveFlag) {}

    public record PlayerEntry(
            int entityId,
            int uuidHash,
            int teamId,
            int flags,
            float x,
            float y,
            float z,
            float vx,
            float vy,
            float vz,
            float yaw,
            float pitch,
            float hpEst,
            int mainHandCat,
            int offHandCat,
            int armorTierSum
    ) {}

    public record ThreatEntry(int type, float x, float y, float z, float vx, float vy, float vz, float extra) {}

    public record SoundEntry(int soundCat, float x, float y, float z, float volume) {}
}
