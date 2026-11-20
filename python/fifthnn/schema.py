"""
Shared canonical schema constants for 5ifthNN (v1).

MUST stay 100% bit-for-bit synchronized with Java's:
  - io.fifthnn.schema.FifthNNSchema
  - io.fifthnn.inference.FeatureEncoder
"""

from __future__ import annotations

import math
import numpy as np

# Binary file format magic & version
FILE_MAGIC = b"5NN1"
FRAME_MAGIC = b"FRM1"
SCHEMA_VERSION = 1

# Raw recording spatial dimensions (world-aligned in .5nn.gz for lossless 1:1 replay)
RAW_VOXEL_RADIUS_XZ = 6
RAW_VOXEL_SIZE_XZ = RAW_VOXEL_RADIUS_XZ * 2 + 1  # 13
RAW_VOXEL_MIN_Y = -3
RAW_VOXEL_MAX_Y = 3
RAW_VOXEL_SIZE_Y = RAW_VOXEL_MAX_Y - RAW_VOXEL_MIN_Y + 1  # 7
RAW_VOXEL_COUNT = RAW_VOXEL_SIZE_XZ * RAW_VOXEL_SIZE_Y * RAW_VOXEL_SIZE_XZ  # 1183

# Egocentric neural network spatial input dimensions (rotated to player yaw)
NN_VOXEL_RADIUS_XZ = 4
NN_VOXEL_SIZE_XZ = NN_VOXEL_RADIUS_XZ * 2 + 1  # 9
NN_VOXEL_MIN_Y = -2
NN_VOXEL_MAX_Y = 2
NN_VOXEL_SIZE_Y = NN_VOXEL_MAX_Y - NN_VOXEL_MIN_Y + 1  # 5
NN_VOXEL_COUNT = NN_VOXEL_SIZE_XZ * NN_VOXEL_SIZE_Y * NN_VOXEL_SIZE_XZ  # 405

LIDAR_RAY_COUNT = 32
MAX_PLAYERS_RAW = 16
MAX_THREATS_RAW = 16
MAX_LANDMARKS_RAW = 24
MAX_SOUNDS_RAW = 8
INVENTORY_SLOT_COUNT = 41  # 36 main + 4 armor + 1 offhand
MAX_GUI_SLOTS = 54

# Neural network observation sizes
NN_MAX_PLAYERS = 6
NN_PLAYER_FEAT_DIM = 18
NN_MAX_THREATS = 6
NN_THREAT_FEAT_DIM = 10
NN_LIDAR_DIM = LIDAR_RAY_COUNT * 4  # 128
NN_SELF_MATCH_DIM = 160
NN_SCALAR_OBS_DIM = (
    NN_LIDAR_DIM
    + NN_MAX_PLAYERS * NN_PLAYER_FEAT_DIM
    + NN_MAX_THREATS * NN_THREAT_FEAT_DIM
    + NN_SELF_MATCH_DIM
)  # 128 + 108 + 60 + 160 = 456

# Model hidden dimensions (Two-Timescale Unified Pure NN, ~2.0M params)
SLOW_HIDDEN_DIM = 192
FAST_HIDDEN_DIM = 384
SLOW_UPDATE_INTERVAL = 10  # ticks (2 Hz at 20 TPS)

# Action space dimensions
NUM_MOVEMENT_KEYS = 7  # W, A, S, D, Jump, Sneak, Sprint
NUM_AIM_BINS = 25      # Non-linear Mu-Law bins for Δyaw and Δpitch
MAX_YAW_DEG_PER_TICK = 35.0
MAX_PITCH_DEG_PER_TICK = 25.0
MU_LAW_MU = 12.0
NUM_COMBAT_STATES = 3  # 0=none, 1=tap, 2=hold (for left and right independently)
NUM_HOTBAR_ACTIONS = 10  # 0=keep, 1..9=slot 0..8
NUM_GUI_ACTIONS = 56     # 0=noop, 1=close_gui, 2..55=slot 0..53

# Block semantic categories (stored in bits 0..5 of each voxel uint8)
BLOCK_CAT_NAMES = [
    "AIR",
    "SOLID_MAP",
    "WOOL",
    "WOOD_PLANKS",
    "ENDSTONE",
    "TERRACOTTA",
    "BLASTPROOF_GLASS",
    "OBSIDIAN",
    "LADDER_VINE",
    "WATER",
    "LAVA_FIRE",
    "SLAB_STAIR_FENCE",
    "BED_ENEMY",
    "BED_OWN",
    "CHEST_ENDERCHEST",
    "GENERATOR_BLOCK",
    "COBWEB_SLIME",
    "SOLID_BREAKABLE_OTHER",
]
NUM_BLOCK_CATS = 32  # reserve up to 32 for embedding stability

# Canonical Item categories (0..47)
ITEM_CAT_NAMES = [
    "EMPTY",
    "WOOL",
    "WOOD_PLANKS",
    "ENDSTONE",
    "TERRACOTTA",
    "BLASTPROOF_GLASS",
    "OBSIDIAN",
    "LADDER",
    "WOOD_SWORD",
    "STONE_SWORD",
    "IRON_SWORD",
    "DIAMOND_SWORD",
    "KB_STICK",
    "WOOD_PICKAXE",
    "IRON_PICKAXE",
    "GOLD_PICKAXE",
    "DIAMOND_PICKAXE",
    "WOOD_AXE",
    "STONE_AXE",
    "IRON_AXE",
    "DIAMOND_AXE",
    "SHEARS",
    "BOW",
    "ARROW",
    "IRON_INGOT",
    "GOLD_INGOT",
    "DIAMOND",
    "EMERALD",
    "GOLDEN_APPLE",
    "FIREBALL",
    "TNT",
    "ENDER_PEARL",
    "WATER_BUCKET",
    "BRIDGE_EGG",
    "POPUP_TOWER",
    "SPONGE",
    "SILVERFISH_EGG",
    "IRON_GOLEM_EGG",
    "POTION_SPEED",
    "POTION_JUMP",
    "POTION_INVIS",
    "COMPASS",
    "LEATHER_ARMOR",
    "CHAIN_ARMOR",
    "IRON_ARMOR",
    "DIAMOND_ARMOR",
    "OTHER_BLOCK",
    "OTHER_ITEM",
]
NUM_ITEM_CATS = len(ITEM_CAT_NAMES)

# Match phases
MATCH_PHASE_UNKNOWN = 0
MATCH_PHASE_LOBBY = 1
MATCH_PHASE_COUNTDOWN = 2
MATCH_PHASE_IN_GAME = 3
MATCH_PHASE_RESPAWNING = 4
MATCH_PHASE_SPECTATING = 5
MATCH_PHASE_POST_GAME = 6

# Frame quality flag bits
QFLAG_VALID_IN_GAME = 1 << 0
QFLAG_WINDOW_FOCUSED = 1 << 1
QFLAG_PAUSE_OR_CHAT = 1 << 2
QFLAG_DAGGER_TAKEOVER = 1 << 3
QFLAG_AI_ACTIVE = 1 << 4
QFLAG_DEAD_OR_RESPAWN = 1 << 5
QFLAG_LOBBY_OR_SPEC = 1 << 6

# Key mask bits
KEY_W = 1 << 0
KEY_A = 1 << 1
KEY_S = 1 << 2
KEY_D = 1 << 3
KEY_JUMP = 1 << 4
KEY_SNEAK = 1 << 5
KEY_SPRINT = 1 << 6
KEY_DROP = 1 << 7
KEY_SWAP_OFFHAND = 1 << 8
KEY_OPEN_INV = 1 << 9
KEY_LEFT_HELD = 1 << 10
KEY_RIGHT_HELD = 1 << 11

# GUI types
GUI_NONE = 0
GUI_PLAYER_INV = 1
GUI_ITEM_SHOP = 2
GUI_UPGRADE_SHOP = 3
GUI_TEAM_CHEST = 4
GUI_ENDER_CHEST = 5
GUI_PAUSE = 6
GUI_CHAT = 7
GUI_OTHER = 8

# Event flags per tick
EVT_BED_BROKEN_BY_ME = 1 << 0
EVT_OWN_BED_LOST = 1 << 1
EVT_KILL = 1 << 2
EVT_FINAL_KILL = 1 << 3
EVT_DEATH = 1 << 4
EVT_VOID_FALL = 1 << 5
EVT_GAME_WON = 1 << 6
EVT_GAME_LOST = 1 << 7
EVT_DAMAGE_DEALT = 1 << 8
EVT_DAMAGE_TAKEN = 1 << 9
EVT_BLOCK_PLACED = 1 << 10
EVT_BLOCK_BROKEN = 1 << 11
EVT_ITEM_BOUGHT = 1 << 12

# Recording modes
RECORDING_MODES = [
    "FULL_MATCH",
    "DRILL_BRIDGING",
    "DRILL_SHOP",
    "DRILL_PVP",
    "DRILL_BED_BREAK",
    "DAGGER_TAKEOVER",
]


def build_mulaw_bin_centers(num_bins: int, max_deg: float, mu: float = MU_LAW_MU) -> np.ndarray:
    """
    Build symmetric Mu-Law bin centers in degrees.
    Matches Java's FifthNNSchema.buildMuLawBinCenters() bit-for-bit.
    """
    lin = np.linspace(-1.0, 1.0, num_bins, dtype=np.float64)
    # Inverse mu-law expansion: denser resolution near 0 deg, wider at edges
    expanded = np.sign(lin) * (((1.0 + mu) ** np.abs(lin) - 1.0) / mu)
    return (expanded * max_deg).astype(np.float32)


YAW_BIN_CENTERS = build_mulaw_bin_centers(NUM_AIM_BINS, MAX_YAW_DEG_PER_TICK)
PITCH_BIN_CENTERS = build_mulaw_bin_centers(NUM_AIM_BINS, MAX_PITCH_DEG_PER_TICK)


def quantize_angle_mulaw(delta_deg: float, max_deg: float, num_bins: int = NUM_AIM_BINS) -> int:
    """Encode a continuous delta angle into nearest Mu-Law discrete bin index [0, num_bins-1]."""
    clamped = max(-max_deg, min(max_deg, float(delta_deg)))
    norm = clamped / max_deg
    compressed = math.copysign(math.log1p(MU_LAW_MU * abs(norm)) / math.log1p(MU_LAW_MU), norm)
    idx = int(round((compressed + 1.0) * 0.5 * (num_bins - 1)))
    return max(0, min(num_bins - 1, idx))


def dequantize_angle_mulaw(bin_idx: int, max_deg: float, num_bins: int = NUM_AIM_BINS) -> float:
    """Decode a Mu-Law discrete bin index back into degrees/tick."""
    u = (2.0 * bin_idx / (num_bins - 1)) - 1.0
    expanded = math.copysign(((1.0 + MU_LAW_MU) ** abs(u) - 1.0) / MU_LAW_MU, u)
    return float(expanded * max_deg)
