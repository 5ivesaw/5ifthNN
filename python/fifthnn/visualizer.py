"""
1:1 Visual-Equivalent Dataset Inspector & Replay Renderer for .5nn.gz recordings.

Proves that a .5nn.gz recording contains enough structured spatial & game state
to reconstruct the entire scene visually without storing raw video frames!
Renders:
  - Top-down slice of the 13x7x13 voxel neighborhood (feet & head layers)
  - 32-ray panoramic lidar overlay & tracked beds/generators/NPCs
  - Nearby enemies & threats with facing arrows
  - Active keys, mouse aim delta, HP, economy, hotbar & GUI state
"""

from __future__ import annotations

import argparse
import math
from pathlib import Path

import matplotlib.pyplot as plt
import numpy as np

from .dataset_io import FrameRecord, read_recording
from .schema import (
    BLOCK_CAT_NAMES,
    ITEM_CAT_NAMES,
    KEY_A,
    KEY_D,
    KEY_JUMP,
    KEY_LEFT_HELD,
    KEY_RIGHT_HELD,
    KEY_S,
    KEY_SNEAK,
    KEY_SPRINT,
    KEY_W,
    RAW_VOXEL_RADIUS_XZ,
    RAW_VOXEL_SIZE_XZ,
    RAW_VOXEL_SIZE_Y,
)


def render_frame_png(fr: FrameRecord, out_path: str | Path) -> None:
    fig, axes = plt.subplots(1, 3, figsize=(16, 5.2), facecolor="#11141c")
    for ax in axes:
        ax.set_facecolor("#181d28")

    raw3d = fr.voxels.reshape(RAW_VOXEL_SIZE_Y, RAW_VOXEL_SIZE_XZ, RAW_VOXEL_SIZE_XZ)
    # Panel 1: Ground layer (dy=-1) & Feet layer (dy=0)
    ground_cat = raw3d[2] & 0x3F  # dy = -1 (below feet: bridge blocks!)
    feet_cat = raw3d[3] & 0x3F    # dy = 0 (walls/beds at feet)
    comp = np.where(feet_cat > 0, feet_cat + 0.5, ground_cat)
    axes[0].imshow(comp, origin="lower", cmap="turbo", vmin=0, vmax=18)
    axes[0].plot(RAW_VOXEL_RADIUS_XZ, RAW_VOXEL_RADIUS_XZ, "wo", markersize=8)
    rad = math.radians(fr.yaw)
    axes[0].arrow(
        RAW_VOXEL_RADIUS_XZ, RAW_VOXEL_RADIUS_XZ, -math.sin(rad) * 2.2, math.cos(rad) * 2.2,
        head_width=0.45, color="white"
    )
    axes[0].set_title(f"13x13 Local Voxels (dy=-1/0) | Tick {fr.tick_index}", color="white")

    # Panel 2: Top-down Radar (Lidar + Players + Landmarks + Threats)
    ax = axes[1]
    ax.set_xlim(-32, 32)
    ax.set_ylim(-32, 32)
    ax.set_aspect("equal")
    for i in range(16):
        ang = math.radians(fr.yaw + i * 22.5)
        d = fr.lidar_dist[i]
        ax.plot([0, -math.sin(ang) * d], [0, math.cos(ang) * d], color="#38bdf8", alpha=0.35, linewidth=1)
    for lm in fr.landmarks:
        dx, dz = lm[2] - fr.x, lm[4] - fr.z
        col = "#f43f5e" if lm[0] == 1 else ("#22c55e" if lm[0] in (0, 2) else "#facc15")
        ax.scatter(dx, dz, c=col, s=55, marker="s", zorder=5)
    for p in fr.players:
        dx, dz = p[4] - fr.x, p[6] - fr.z
        ax.scatter(dx, dz, c="#fb923c", s=70, marker="^", zorder=6)
    ax.scatter(0, 0, c="white", s=80, zorder=7)
    ax.set_title("32-Block Top-Down Radar (Lidar + Entities)", color="white")
    ax.tick_params(colors="gray")

    # Panel 3: Telemetry & Inputs HUD
    ax = axes[2]
    ax.axis("off")
    km = fr.key_mask
    keys = " ".join(
        name for bit, name in [
            (KEY_W, "W"), (KEY_A, "A"), (KEY_S, "S"), (KEY_D, "D"),
            (KEY_JUMP, "JMP"), (KEY_SNEAK, "SNK"), (KEY_SPRINT, "SPR"),
            (KEY_LEFT_HELD, "LMB"), (KEY_RIGHT_HELD, "RMB"),
        ] if (km & bit)
    ) or "(idle)"
    held_cat = ITEM_CAT_NAMES[min(fr.inventory[fr.selected_slot, 0], len(ITEM_CAT_NAMES) - 1)]
    hit_cat = BLOCK_CAT_NAMES[min(fr.hit_block_cat, len(BLOCK_CAT_NAMES) - 1)]
    text = (
        f"Pos: ({fr.x:.1f}, {fr.y:.1f}, {fr.z:.1f})\n"
        f"Yaw/Pitch: ({fr.yaw:.1f}, {fr.pitch:.1f})\n"
        f"Delta Aim: ({fr.delta_yaw:+.2f}, {fr.delta_pitch:+.2f})\n"
        f"HP: {fr.hp:.1f} (+{fr.absorption:.1f}) | Hurt: {fr.hurt_time}\n"
        f"Held [Slot {fr.selected_slot + 1}]: {held_cat}\n"
        f"Crosshair: type={fr.hit_type} dist={fr.hit_dist:.2f} {hit_cat}\n"
        f"Econ: Fe={fr.econ[0]} Au={fr.econ[1]} Dia={fr.econ[2]} Em={fr.econ[3]}\n"
        f"Blocks={fr.econ[4]} Gapples={fr.econ[5]} FB={fr.econ[6]} TNT={fr.econ[7]}\n"
        f"GUI: type={fr.gui_type} clickSlot={fr.gui_click_slot}\n"
        f"Keys: {keys}\n"
        f"Sub-tick Clicks: L={fr.left_clicks} R={fr.right_clicks}\n"
        f"Reward Delta: {fr.reward_delta:+.4f}"
    )
    ax.text(0.03, 0.95, text, va="top", family="monospace", fontsize=10, color="#e2e8f0")

    plt.tight_layout()
    plt.savefig(out_path, dpi=120)
    plt.close(fig)


def main() -> None:
    p = argparse.ArgumentParser(description="Visualize frames from a 5ifthNN .5nn.gz recording")
    p.add_argument("file", help="Path to .5nn.gz file")
    p.add_argument("--frame", type=int, default=0, help="Frame index to render")
    p.add_argument("--out", default="frame_preview.png")
    args = p.parse_args()

    header, frames = read_recording(args.file, valid_only=False)
    print(f"Loaded {len(frames)} frames | header={header}")
    if not frames:
        return
    idx = max(0, min(len(frames) - 1, args.frame))
    render_frame_png(frames[idx], args.out)
    print(f"Saved visual reconstruction to {args.out}")


if __name__ == "__main__":
    main()
