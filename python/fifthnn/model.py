"""
5ifthNN Two-Timescale Unified Pure Neural Network (~2.0M parameters).

Architecture:
  - Spatial Encoder: Embedding(32, 6) + 2 flags over (5, 9, 9) egocentric voxel patch
    -> 2D Conv stack over vertical slice channels -> 128-dim spatial embedding.
  - Scalar/Lidar/Entity Encoder: LayerNorm + Residual MLP -> 192-dim embedding.
  - Slow GRU (192 hidden): Updates every 10 ticks (2 Hz) for long-horizon BedWars memory.
  - Fast GRU (384 hidden): Updates every tick (20 Hz) for crisp 1.8 PvP & bridging control.
  - Factored Policy Heads: Movement(7), Mu-Law Δyaw(25), Mu-Law Δpitch(25),
    Left/Right Click(3+3), Hotbar(10), Masked GUI Slot(56), Value(1).
  - Training-Only Auxiliary Head: Predicts danger/void risk & HP delta (stripped on ONNX export).
"""

from __future__ import annotations

from typing import Dict, Tuple

import torch
import torch.nn as nn
import torch.nn.functional as F

from .schema import (
    FAST_HIDDEN_DIM,
    NN_SCALAR_OBS_DIM,
    NN_VOXEL_SIZE_XZ,
    NN_VOXEL_SIZE_Y,
    NUM_AIM_BINS,
    NUM_BLOCK_CATS,
    NUM_COMBAT_STATES,
    NUM_GUI_ACTIONS,
    NUM_HOTBAR_ACTIONS,
    NUM_MOVEMENT_KEYS,
    SLOW_HIDDEN_DIM,
    SLOW_UPDATE_INTERVAL,
)


class SpatialVoxelEncoder(nn.Module):
    def __init__(self, out_dim: int = 128):
        super().__init__()
        self.embed_dim = 6
        self.block_emb = nn.Embedding(NUM_BLOCK_CATS, self.embed_dim)
        in_channels = NN_VOXEL_SIZE_Y * (self.embed_dim + 2)  # 5 * 8 = 40
        self.conv = nn.Sequential(
            nn.Conv2d(in_channels, 48, kernel_size=3, padding=1),
            nn.SiLU(),
            nn.Conv2d(48, 64, kernel_size=3, stride=2, padding=1),  # 9x9 -> 5x5
            nn.SiLU(),
            nn.Conv2d(64, 64, kernel_size=3, stride=2, padding=1),  # 5x5 -> 3x3
            nn.SiLU(),
        )
        self.proj = nn.Sequential(
            nn.Linear(64 * 3 * 3, out_dim),
            nn.LayerNorm(out_dim),
            nn.SiLU(),
        )

    def forward(self, voxel_cats: torch.Tensor, voxel_flags: torch.Tensor) -> torch.Tensor:
        # voxel_cats: (N, 5, 9, 9) int64
        # voxel_flags: (N, 2, 5, 9, 9) float32
        n = voxel_cats.shape[0]
        emb = self.block_emb(voxel_cats)  # (N, 5, 9, 9, 6)
        emb = emb.permute(0, 1, 4, 2, 3)  # (N, 5, 6, 9, 9)
        flags = voxel_flags.permute(0, 2, 1, 3, 4)  # (N, 5, 2, 9, 9)
        x = torch.cat([emb, flags], dim=2)  # (N, 5, 8, 9, 9)
        x = x.reshape(n, NN_VOXEL_SIZE_Y * (self.embed_dim + 2), NN_VOXEL_SIZE_XZ, NN_VOXEL_SIZE_XZ)
        x = self.conv(x)
        return self.proj(x.reshape(n, -1))


class FifthNNModel(nn.Module):
    def __init__(self, include_aux: bool = True):
        super().__init__()
        self.include_aux = include_aux

        self.spatial_enc = SpatialVoxelEncoder(out_dim=128)
        self.scalar_enc = nn.Sequential(
            nn.LayerNorm(NN_SCALAR_OBS_DIM),
            nn.Linear(NN_SCALAR_OBS_DIM, 256),
            nn.SiLU(),
            nn.LayerNorm(256),
            nn.Linear(256, 192),
            nn.SiLU(),
        )

        fused_dim = 128 + 192  # 320
        self.slow_gru = nn.GRUCell(fused_dim, SLOW_HIDDEN_DIM)
        self.fast_gru = nn.GRUCell(fused_dim + SLOW_HIDDEN_DIM, FAST_HIDDEN_DIM)

        self.head_trunk = nn.Sequential(
            nn.LayerNorm(FAST_HIDDEN_DIM),
            nn.Linear(FAST_HIDDEN_DIM, 256),
            nn.SiLU(),
        )

        # Factored Policy Heads
        self.move_head = nn.Linear(256, NUM_MOVEMENT_KEYS)
        self.yaw_head = nn.Linear(256, NUM_AIM_BINS)
        self.pitch_head = nn.Linear(256, NUM_AIM_BINS)
        self.left_head = nn.Linear(256, NUM_COMBAT_STATES)
        self.right_head = nn.Linear(256, NUM_COMBAT_STATES)
        self.hotbar_head = nn.Linear(256, NUM_HOTBAR_ACTIONS)
        self.gui_head = nn.Linear(256, NUM_GUI_ACTIONS)

        # Critic Value Head (used in PPO / ReST)
        self.value_head = nn.Linear(256, 1)

        # Auxiliary prediction head (training-only: danger/void risk, hp delta)
        if include_aux:
            self.aux_head = nn.Linear(256, 2)

    def init_hidden(self, batch_size: int, device: torch.device) -> Tuple[torch.Tensor, torch.Tensor]:
        h_fast = torch.zeros(batch_size, FAST_HIDDEN_DIM, device=device)
        h_slow = torch.zeros(batch_size, SLOW_HIDDEN_DIM, device=device)
        return h_fast, h_slow

    def step(
        self,
        voxel_cats: torch.Tensor,
        voxel_flags: torch.Tensor,
        scalar_obs: torch.Tensor,
        h_fast: torch.Tensor,
        h_slow: torch.Tensor,
        update_slow: torch.Tensor,
    ) -> Tuple[Dict[str, torch.Tensor], torch.Tensor, torch.Tensor]:
        """Single-tick forward pass (used during both rollout & ONNX export)."""
        sp = self.spatial_enc(voxel_cats, voxel_flags)
        sc = self.scalar_enc(scalar_obs)
        fused = torch.cat([sp, sc], dim=-1)

        cand_slow = self.slow_gru(fused, h_slow)
        # Blend with 0/1 mask so ONNX graph stays completely static & branchless
        h_slow_next = update_slow * cand_slow + (1.0 - update_slow) * h_slow

        fast_in = torch.cat([fused, h_slow_next], dim=-1)
        h_fast_next = self.fast_gru(fast_in, h_fast)

        feat = self.head_trunk(h_fast_next)
        out = {
            "move_logits": self.move_head(feat),
            "yaw_logits": self.yaw_head(feat),
            "pitch_logits": self.pitch_head(feat),
            "left_logits": self.left_head(feat),
            "right_logits": self.right_head(feat),
            "hotbar_logits": self.hotbar_head(feat),
            "gui_logits": self.gui_head(feat),
            "value": self.value_head(feat).squeeze(-1),
        }
        if self.include_aux and hasattr(self, "aux_head"):
            out["aux"] = self.aux_head(feat)
        return out, h_fast_next, h_slow_next

    def forward_sequence(
        self,
        voxel_cats: torch.Tensor,   # (B, T, 5, 9, 9)
        voxel_flags: torch.Tensor,  # (B, T, 2, 5, 9, 9)
        scalar_obs: torch.Tensor,   # (B, T, 456)
        h_fast: torch.Tensor,       # (B, 384)
        h_slow: torch.Tensor,       # (B, 192)
        start_step: int = 0,
    ) -> Tuple[Dict[str, torch.Tensor], torch.Tensor, torch.Tensor]:
        b, t = scalar_obs.shape[:2]
        # Vectorize encoders over B*T for maximum GPU throughput on Colab L4
        sp = self.spatial_enc(
            voxel_cats.reshape(b * t, NN_VOXEL_SIZE_Y, NN_VOXEL_SIZE_XZ, NN_VOXEL_SIZE_XZ),
            voxel_flags.reshape(b * t, 2, NN_VOXEL_SIZE_Y, NN_VOXEL_SIZE_XZ, NN_VOXEL_SIZE_XZ),
        ).reshape(b, t, -1)
        sc = self.scalar_enc(scalar_obs.reshape(b * t, -1)).reshape(b, t, -1)
        fused_seq = torch.cat([sp, sc], dim=-1)  # (B, T, 320)

        fast_states = []
        for step_i in range(t):
            f_t = fused_seq[:, step_i, :]
            if ((start_step + step_i) % SLOW_UPDATE_INTERVAL) == 0:
                h_slow = self.slow_gru(f_t, h_slow)
            h_fast = self.fast_gru(torch.cat([f_t, h_slow], dim=-1), h_fast)
            fast_states.append(h_fast)

        fast_stack = torch.stack(fast_states, dim=1)  # (B, T, 384)
        feat = self.head_trunk(fast_stack)

        out = {
            "move_logits": self.move_head(feat),
            "yaw_logits": self.yaw_head(feat),
            "pitch_logits": self.pitch_head(feat),
            "left_logits": self.left_head(feat),
            "right_logits": self.right_head(feat),
            "hotbar_logits": self.hotbar_head(feat),
            "gui_logits": self.gui_head(feat),
            "value": self.value_head(feat).squeeze(-1),
        }
        if self.include_aux and hasattr(self, "aux_head"):
            out["aux"] = self.aux_head(feat)
        return out, h_fast, h_slow


class OnnxStepWrapper(nn.Module):
    """
    Static ONNX export wrapper (strips training-only auxiliary head).
    Inputs:  voxel_cats(1,5,9,9), voxel_flags(1,2,5,9,9), scalar_obs(1,456),
             h_fast(1,384), h_slow(1,192), update_slow(1,1)
    Outputs: move_logits, yaw_logits, pitch_logits, left_logits, right_logits,
             hotbar_logits, gui_logits, value, h_fast_next, h_slow_next
    """

    def __init__(self, core: FifthNNModel):
        super().__init__()
        self.core = core

    def forward(
        self,
        voxel_cats: torch.Tensor,
        voxel_flags: torch.Tensor,
        scalar_obs: torch.Tensor,
        h_fast: torch.Tensor,
        h_slow: torch.Tensor,
        update_slow: torch.Tensor,
    ):
        out, h_fast_next, h_slow_next = self.core.step(
            voxel_cats, voxel_flags, scalar_obs, h_fast, h_slow, update_slow
        )
        return (
            out["move_logits"],
            out["yaw_logits"],
            out["pitch_logits"],
            out["left_logits"],
            out["right_logits"],
            out["hotbar_logits"],
            out["gui_logits"],
            out["value"],
            h_fast_next,
            h_slow_next,
        )


def count_parameters(model: nn.Module) -> int:
    return sum(p.numel() for p in model.parameters() if p.requires_grad)


if __name__ == "__main__":
    m = FifthNNModel(include_aux=False)
    print(f"5ifthNN Inference Parameter Count: {count_parameters(m):,}")
