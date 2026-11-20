package io.fifthnn.schema;

/**
 * Shared canonical schema constants for 5ifthNN (v1).
 * MUST stay 100% bit-for-bit synchronized with python/fifthnn/schema.py.
 */
public final class FifthNNSchema {
    private FifthNNSchema() {}

    public static final byte[] FILE_MAGIC = new byte[]{'5', 'N', 'N', '1'};
    public static final byte[] FRAME_MAGIC = new byte[]{'F', 'R', 'M', '1'};
    public static final int SCHEMA_VERSION = 1;

    // Raw world-aligned 1:1 local voxel cube in .5nn.gz (13 x 7 x 13 = 1,183 cells)
    public static final int RAW_VOXEL_RADIUS_XZ = 6;
    public static final int RAW_VOXEL_SIZE_XZ = RAW_VOXEL_RADIUS_XZ * 2 + 1; // 13
    public static final int RAW_VOXEL_MIN_Y = -3;
    public static final int RAW_VOXEL_MAX_Y = 3;
    public static final int RAW_VOXEL_SIZE_Y = RAW_VOXEL_MAX_Y - RAW_VOXEL_MIN_Y + 1; // 7
    public static final int RAW_VOXEL_COUNT = RAW_VOXEL_SIZE_XZ * RAW_VOXEL_SIZE_Y * RAW_VOXEL_SIZE_XZ; // 1183

    // Egocentric neural network spatial voxel patch (9 x 5 x 9 = 405 cells)
    public static final int NN_VOXEL_RADIUS_XZ = 4;
    public static final int NN_VOXEL_SIZE_XZ = NN_VOXEL_RADIUS_XZ * 2 + 1; // 9
    public static final int NN_VOXEL_MIN_Y = -2;
    public static final int NN_VOXEL_MAX_Y = 2;
    public static final int NN_VOXEL_SIZE_Y = NN_VOXEL_MAX_Y - NN_VOXEL_MIN_Y + 1; // 5

    public static final int LIDAR_RAY_COUNT = 32;
    public static final int MAX_PLAYERS_RAW = 16;
    public static final int MAX_THREATS_RAW = 16;
    public static final int MAX_LANDMARKS_RAW = 24;
    public static final int MAX_SOUNDS_RAW = 8;
    public static final int INVENTORY_SLOT_COUNT = 41; // 36 main + 4 armor + 1 offhand
    public static final int MAX_GUI_SLOTS = 54;

    // Neural network observation vector sizes
    public static final int NN_MAX_PLAYERS = 6;
    public static final int NN_PLAYER_FEAT_DIM = 18;
    public static final int NN_MAX_THREATS = 6;
    public static final int NN_THREAT_FEAT_DIM = 10;
    public static final int NN_LIDAR_DIM = LIDAR_RAY_COUNT * 4; // 128
    public static final int NN_SELF_MATCH_DIM = 160;
    public static final int NN_SCALAR_OBS_DIM =
            NN_LIDAR_DIM + NN_MAX_PLAYERS * NN_PLAYER_FEAT_DIM + NN_MAX_THREATS * NN_THREAT_FEAT_DIM + NN_SELF_MATCH_DIM; // 456

    // Two-timescale GRU hidden sizes (~1.8M-2.0M params total)
    public static final int SLOW_HIDDEN_DIM = 192;
    public static final int FAST_HIDDEN_DIM = 384;
    public static final int SLOW_UPDATE_INTERVAL = 10; // ticks (2 Hz)

    // Action space dimensions
    public static final int NUM_MOVEMENT_KEYS = 7;
    public static final int NUM_AIM_BINS = 25;
    public static final float MAX_YAW_DEG_PER_TICK = 35.0f;
    public static final float MAX_PITCH_DEG_PER_TICK = 25.0f;
    public static final double MU_LAW_MU = 12.0;
    public static final int NUM_COMBAT_STATES = 3; // 0=none, 1=tap, 2=hold
    public static final int NUM_HOTBAR_ACTIONS = 10; // 0=keep, 1..9=slot 0..8
    public static final int NUM_GUI_ACTIONS = 56; // 0=noop, 1=close_gui, 2..55=slot 0..53

    // Block semantic categories (bits 0..5)
    public static final int NUM_BLOCK_CATS = 32;
    public static final int BLOCK_AIR = 0;
    public static final int BLOCK_SOLID_MAP = 1;
    public static final int BLOCK_WOOL = 2;
    public static final int BLOCK_WOOD_PLANKS = 3;
    public static final int BLOCK_ENDSTONE = 4;
    public static final int BLOCK_TERRACOTTA = 5;
    public static final int BLOCK_BLASTPROOF_GLASS = 6;
    public static final int BLOCK_OBSIDIAN = 7;
    public static final int BLOCK_LADDER_VINE = 8;
    public static final int BLOCK_WATER = 9;
    public static final int BLOCK_LAVA_FIRE = 10;
    public static final int BLOCK_SLAB_STAIR_FENCE = 11;
    public static final int BLOCK_BED_ENEMY = 12;
    public static final int BLOCK_BED_OWN = 13;
    public static final int BLOCK_CHEST_ENDERCHEST = 14;
    public static final int BLOCK_GENERATOR = 15;
    public static final int BLOCK_COBWEB_SLIME = 16;
    public static final int BLOCK_SOLID_BREAKABLE_OTHER = 17;

    // Canonical Item categories (0..47)
    public static final int NUM_ITEM_CATS = 48;
    public static final int ITEM_EMPTY = 0;
    public static final int ITEM_WOOL = 1;
    public static final int ITEM_WOOD_PLANKS = 2;
    public static final int ITEM_ENDSTONE = 3;
    public static final int ITEM_TERRACOTTA = 4;
    public static final int ITEM_BLASTPROOF_GLASS = 5;
    public static final int ITEM_OBSIDIAN = 6;
    public static final int ITEM_LADDER = 7;
    public static final int ITEM_WOOD_SWORD = 8;
    public static final int ITEM_STONE_SWORD = 9;
    public static final int ITEM_IRON_SWORD = 10;
    public static final int ITEM_DIAMOND_SWORD = 11;
    public static final int ITEM_KB_STICK = 12;
    public static final int ITEM_WOOD_PICKAXE = 13;
    public static final int ITEM_IRON_PICKAXE = 14;
    public static final int ITEM_GOLD_PICKAXE = 15;
    public static final int ITEM_DIAMOND_PICKAXE = 16;
    public static final int ITEM_WOOD_AXE = 17;
    public static final int ITEM_STONE_AXE = 18;
    public static final int ITEM_IRON_AXE = 19;
    public static final int ITEM_DIAMOND_AXE = 20;
    public static final int ITEM_SHEARS = 21;
    public static final int ITEM_BOW = 22;
    public static final int ITEM_ARROW = 23;
    public static final int ITEM_IRON_INGOT = 24;
    public static final int ITEM_GOLD_INGOT = 25;
    public static final int ITEM_DIAMOND = 26;
    public static final int ITEM_EMERALD = 27;
    public static final int ITEM_GOLDEN_APPLE = 28;
    public static final int ITEM_FIREBALL = 29;
    public static final int ITEM_TNT = 30;
    public static final int ITEM_ENDER_PEARL = 31;
    public static final int ITEM_WATER_BUCKET = 32;
    public static final int ITEM_BRIDGE_EGG = 33;
    public static final int ITEM_POPUP_TOWER = 34;
    public static final int ITEM_SPONGE = 35;
    public static final int ITEM_SILVERFISH_EGG = 36;
    public static final int ITEM_IRON_GOLEM_EGG = 37;
    public static final int ITEM_POTION_SPEED = 38;
    public static final int ITEM_POTION_JUMP = 39;
    public static final int ITEM_POTION_INVIS = 40;
    public static final int ITEM_COMPASS = 41;
    public static final int ITEM_LEATHER_ARMOR = 42;
    public static final int ITEM_CHAIN_ARMOR = 43;
    public static final int ITEM_IRON_ARMOR = 44;
    public static final int ITEM_DIAMOND_ARMOR = 45;
    public static final int ITEM_OTHER_BLOCK = 46;
    public static final int ITEM_OTHER_ITEM = 47;

    // Match phases
    public static final int MATCH_PHASE_UNKNOWN = 0;
    public static final int MATCH_PHASE_LOBBY = 1;
    public static final int MATCH_PHASE_COUNTDOWN = 2;
    public static final int MATCH_PHASE_IN_GAME = 3;
    public static final int MATCH_PHASE_RESPAWNING = 4;
    public static final int MATCH_PHASE_SPECTATING = 5;
    public static final int MATCH_PHASE_POST_GAME = 6;

    // Quality flags
    public static final int QFLAG_VALID_IN_GAME = 1 << 0;
    public static final int QFLAG_WINDOW_FOCUSED = 1 << 1;
    public static final int QFLAG_PAUSE_OR_CHAT = 1 << 2;
    public static final int QFLAG_DAGGER_TAKEOVER = 1 << 3;
    public static final int QFLAG_AI_ACTIVE = 1 << 4;
    public static final int QFLAG_DEAD_OR_RESPAWN = 1 << 5;
    public static final int QFLAG_LOBBY_OR_SPEC = 1 << 6;

    // Self flags
    public static final int SFLAG_ON_GROUND = 1 << 0;
    public static final int SFLAG_SNEAKING = 1 << 1;
    public static final int SFLAG_SPRINTING = 1 << 2;
    public static final int SFLAG_IN_WATER = 1 << 3;
    public static final int SFLAG_ON_FIRE = 1 << 4;
    public static final int SFLAG_SWIMMING = 1 << 5;
    public static final int SFLAG_USING_ITEM = 1 << 6;
    public static final int SFLAG_FALL_FLYING = 1 << 7;

    // Key mask bits
    public static final int KEY_W = 1 << 0;
    public static final int KEY_A = 1 << 1;
    public static final int KEY_S = 1 << 2;
    public static final int KEY_D = 1 << 3;
    public static final int KEY_JUMP = 1 << 4;
    public static final int KEY_SNEAK = 1 << 5;
    public static final int KEY_SPRINT = 1 << 6;
    public static final int KEY_DROP = 1 << 7;
    public static final int KEY_SWAP_OFFHAND = 1 << 8;
    public static final int KEY_OPEN_INV = 1 << 9;
    public static final int KEY_LEFT_HELD = 1 << 10;
    public static final int KEY_RIGHT_HELD = 1 << 11;

    // GUI types
    public static final int GUI_NONE = 0;
    public static final int GUI_PLAYER_INV = 1;
    public static final int GUI_ITEM_SHOP = 2;
    public static final int GUI_UPGRADE_SHOP = 3;
    public static final int GUI_TEAM_CHEST = 4;
    public static final int GUI_ENDER_CHEST = 5;
    public static final int GUI_PAUSE = 6;
    public static final int GUI_CHAT = 7;
    public static final int GUI_OTHER = 8;

    // Landmark types
    public static final int LM_OWN_BED = 0;
    public static final int LM_ENEMY_BED = 1;
    public static final int LM_HOME_SPAWN = 2;
    public static final int LM_DIAMOND_GEN = 3;
    public static final int LM_EMERALD_GEN = 4;
    public static final int LM_SHOP_NPC = 5;
    public static final int LM_UPGRADE_NPC = 6;

    // Threat / Dropped entity types
    public static final int THREAT_FIREBALL = 1;
    public static final int THREAT_TNT = 2;
    public static final int THREAT_ARROW = 3;
    public static final int THREAT_ENDER_PEARL = 4;
    public static final int THREAT_EGG = 5;
    public static final int THREAT_MOB = 6;
    public static final int THREAT_DROP_IRON = 7;
    public static final int THREAT_DROP_GOLD = 8;
    public static final int THREAT_DROP_DIAMOND = 9;
    public static final int THREAT_DROP_EMERALD = 10;
    public static final int THREAT_DROP_OTHER = 11;

    // Sound categories
    public static final int SND_FOOTSTEP = 0;
    public static final int SND_BLOCK_PLACE = 1;
    public static final int SND_BLOCK_BREAK = 2;
    public static final int SND_BED_DESTROY = 3;
    public static final int SND_TNT_FUSE = 4;
    public static final int SND_EXPLOSION = 5;
    public static final int SND_FIREBALL = 6;
    public static final int SND_PEARL = 7;
    public static final int SND_POTION_DRINK = 8;
    public static final int SND_HIT_HURT = 9;

    // Event flags per tick
    public static final int EVT_BED_BROKEN_BY_ME = 1 << 0;
    public static final int EVT_OWN_BED_LOST = 1 << 1;
    public static final int EVT_KILL = 1 << 2;
    public static final int EVT_FINAL_KILL = 1 << 3;
    public static final int EVT_DEATH = 1 << 4;
    public static final int EVT_VOID_FALL = 1 << 5;
    public static final int EVT_GAME_WON = 1 << 6;
    public static final int EVT_GAME_LOST = 1 << 7;
    public static final int EVT_DAMAGE_DEALT = 1 << 8;
    public static final int EVT_DAMAGE_TAKEN = 1 << 9;
    public static final int EVT_BLOCK_PLACED = 1 << 10;
    public static final int EVT_BLOCK_BROKEN = 1 << 11;
    public static final int EVT_ITEM_BOUGHT = 1 << 12;

    public enum RecordingMode {
        FULL_MATCH("Full Match", "Complete Solo/Doubles BedWars matches"),
        DRILL_BRIDGING("Bridging Drill", "Speed-bridging, diagonal bridges, near-ledge clutches"),
        DRILL_SHOP("Shop Drill", "Fast item shop & team upgrade purchases"),
        DRILL_PVP("1.8 PvP Drill", "1.8 sprint-reset, strafing, knockback & bridge duels"),
        DRILL_BED_BREAK("Bed Break Drill", "Breaking wool/wood/endstone defenses & rushing beds"),
        DAGGER_TAKEOVER("HG-DAgger Takeover", "Live human corrective overrides while AI drives");

        public final String displayName;
        public final String description;

        RecordingMode(String displayName, String description) {
            this.displayName = displayName;
            this.description = description;
        }

        public RecordingMode next() {
            RecordingMode[] vals = values();
            return vals[(ordinal() + 1) % vals.length];
        }

        public static RecordingMode fromName(String raw) {
            for (RecordingMode m : values()) {
                if (m.name().equalsIgnoreCase(raw)) {
                    return m;
                }
            }
            return FULL_MATCH;
        }
    }

    public enum AiControlMode {
        OFF("Off"),
        INFER("Pure NN Control"),
        HG_DAGGER("HG-DAgger (AI + Live Takeover)");

        public final String displayName;

        AiControlMode(String displayName) {
            this.displayName = displayName;
        }

        public AiControlMode next() {
            AiControlMode[] vals = values();
            return vals[(ordinal() + 1) % vals.length];
        }
    }

    public static float dequantizeAngleMuLaw(int binIdx, float maxDeg) {
        int clamped = Math.max(0, Math.min(NUM_AIM_BINS - 1, binIdx));
        double u = (2.0 * clamped / (NUM_AIM_BINS - 1)) - 1.0;
        double expanded = Math.copySign((Math.pow(1.0 + MU_LAW_MU, Math.abs(u)) - 1.0) / MU_LAW_MU, u);
        return (float) (expanded * maxDeg);
    }

    public static int quantizeAngleMuLaw(float deltaDeg, float maxDeg) {
        double clamped = Math.max(-maxDeg, Math.min(maxDeg, deltaDeg));
        double norm = clamped / maxDeg;
        double compressed = Math.copySign(Math.log1p(MU_LAW_MU * Math.abs(norm)) / Math.log1p(MU_LAW_MU), norm);
        int idx = (int) Math.round((compressed + 1.0) * 0.5 * (NUM_AIM_BINS - 1));
        return Math.max(0, Math.min(NUM_AIM_BINS - 1, idx));
    }

    public static int serverProfileToId(String profile) {
        if (profile == null || profile.isEmpty()) return 0;
        return Math.floorMod(profile.hashCode(), 8);
    }
}
