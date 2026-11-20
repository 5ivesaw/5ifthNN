"""
Phase 2A: Behavior Cloning (BC) & HG-DAgger Trainer for 5ifthNN (Colab L4 ready).

Features:
  - Truncated BPTT over 128-tick windows (6.4 s) for Two-Timescale GRU training
  - Automatic Left-Right Mirror Augmentation (2x free dataset!)
  - Event Oversampling (Shop 4x, Bed-break / Edge-clutch 5x, Combat 2x, DAgger takeover 3x)
  - Mu-Law categorical Cross-Entropy for crisp human-like camera aim
  - Masked GUI slot Cross-Entropy (only trained when GUI is open)
  - Auxiliary danger/HP-delta loss to accelerate spatial convergence on small datasets (1-5 hrs)
"""

from __future__ import annotations

import argparse
import glob
import math
import random
from pathlib import Path
from typing import Dict, List, Tuple

import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F
from torch.utils.data import DataLoader, Dataset

from .dataset_io import FrameRecord, read_recording
from .features import encode_frame_obs, encode_frame_targets
from .model import FifthNNModel, count_parameters
from .schema import (
    EVT_BED_BROKEN_BY_ME,
    EVT_DAMAGE_DEALT,
    EVT_DAMAGE_TAKEN,
    EVT_ITEM_BOUGHT,
    QFLAG_DAGGER_TAKEOVER,
)


class BedWarsSequenceDataset(Dataset):
    """Slices verified .5nn.gz recordings into overlapping temporal windows with mirror aug."""

    def __init__(
        self,
        recording_paths: List[str],
        seq_len: int = 128,
        stride: int = 64,
        mirror_aug: bool = True,
    ):
        self.seq_len = seq_len
        self.samples: List[Tuple[int, int, bool, float]] = []
        self.episodes: List[Tuple[Dict, List[FrameRecord]]] = []

        for path in recording_paths:
            header, frames = read_recording(path, valid_only=True)
            if len(frames) < seq_len + 2:
                continue
            ep_idx = len(self.episodes)
            self.episodes.append((header, frames))

            for start in range(0, len(frames) - seq_len - 1, stride):
                window = frames[start : start + seq_len]
                weight = self._compute_window_weight(window)
                self.samples.append((ep_idx, start, False, weight))
                if mirror_aug:
                    self.samples.append((ep_idx, start, True, weight))

    @staticmethod
    def _compute_window_weight(window: List[FrameRecord]) -> float:
        w = 1.0
        has_gui = any(fr.gui_type > 0 or fr.gui_click_slot >= 0 for fr in window)
        has_bed = any(fr.event_flags & EVT_BED_BROKEN_BY_ME for fr in window)
        has_combat = any(fr.event_flags & (EVT_DAMAGE_DEALT | EVT_DAMAGE_TAKEN) for fr in window)
        has_dagger = any(fr.quality_flags & QFLAG_DAGGER_TAKEOVER for fr in window)
        has_buy = any(fr.event_flags & EVT_ITEM_BOUGHT for fr in window)
        if has_gui or has_buy:
            w = max(w, 4.0)
        if has_bed:
            w = max(w, 5.0)
        if has_combat:
            w = max(w, 2.0)
        if has_dagger:
            w = max(w, 3.0)
        return w

    def __len__(self) -> int:
        return len(self.samples)

    def __getitem__(self, idx: int) -> Dict[str, torch.Tensor]:
        ep_idx, start, mirror, weight = self.samples[idx]
        header, frames = self.episodes[ep_idx]
        pvp18 = header.get("pvpMode", "1.8") == "1.8"
        srv_id = hash(header.get("serverProfile", "local")) & 0x7

        vc_list, vf_list, obs_list = [], [], []
        move_list, yaw_list, pitch_list = [], [], []
        left_list, right_list, hotbar_list, gui_list, gui_open_list = [], [], [], [], []
        aux_list = []

        for t in range(start, start + self.seq_len):
            fr = frames[t]
            fr_next = frames[min(t + 20, len(frames) - 1)]
            vc, vf, obs = encode_frame_obs(fr, server_profile_id=srv_id, pvp_mode_18=pvp18, mirror=mirror)
            tgt = encode_frame_targets(fr, mirror=mirror)

            vc_list.append(vc)
            vf_list.append(vf)
            obs_list.append(obs)
            move_list.append(tgt["move"])
            yaw_list.append(tgt["yaw_bin"])
            pitch_list.append(tgt["pitch_bin"])
            left_list.append(tgt["left_state"])
            right_list.append(tgt["right_state"])
            hotbar_list.append(tgt["hotbar"])
            gui_list.append(tgt["gui_action"])
            gui_open_list.append(tgt["gui_open"])

            # Auxiliary targets: [1s future void/fall danger, 1s normalized HP delta]
            danger = 1.0 if (fr_next.y < fr.y - 4.0 or fr_next.hp < fr.hp - 2.0) else 0.0
            hp_delta = max(-1.0, min(1.0, (fr_next.hp - fr.hp) / 20.0))
            aux_list.append([danger, hp_delta])

        return {
            "voxel_cats": torch.from_numpy(np.stack(vc_list)),
            "voxel_flags": torch.from_numpy(np.stack(vf_list)),
            "scalar_obs": torch.from_numpy(np.stack(obs_list)),
            "move": torch.from_numpy(np.stack(move_list)),
            "yaw_bin": torch.tensor(yaw_list, dtype=torch.long),
            "pitch_bin": torch.tensor(pitch_list, dtype=torch.long),
            "left_state": torch.tensor(left_list, dtype=torch.long),
            "right_state": torch.tensor(right_list, dtype=torch.long),
            "hotbar": torch.tensor(hotbar_list, dtype=torch.long),
            "gui_action": torch.tensor(gui_list, dtype=torch.long),
            "gui_open": torch.tensor(gui_open_list, dtype=torch.float32),
            "aux_target": torch.tensor(aux_list, dtype=torch.float32),
            "sample_weight": torch.tensor(weight, dtype=torch.float32),
        }


def compute_bc_loss(out: Dict[str, torch.Tensor], batch: Dict[str, torch.Tensor]) -> Tuple[torch.Tensor, Dict[str, float]]:
    w = batch["sample_weight"].unsqueeze(1)  # (B, 1)

    loss_move = (F.binary_cross_entropy_with_logits(out["move_logits"], batch["move"], reduction="none").mean(dim=-1) * w).mean()
    loss_yaw = (F.cross_entropy(out["yaw_logits"].transpose(1, 2), batch["yaw_bin"], reduction="none") * w).mean()
    loss_pitch = (F.cross_entropy(out["pitch_logits"].transpose(1, 2), batch["pitch_bin"], reduction="none") * w).mean()
    loss_left = (F.cross_entropy(out["left_logits"].transpose(1, 2), batch["left_state"], reduction="none") * w).mean()
    loss_right = (F.cross_entropy(out["right_logits"].transpose(1, 2), batch["right_state"], reduction="none") * w).mean()
    loss_hotbar = (F.cross_entropy(out["hotbar_logits"].transpose(1, 2), batch["hotbar"], reduction="none") * w).mean()

    # Masked GUI loss: only supervise GUI slot head on ticks where a GUI is actually open
    gui_mask = batch["gui_open"] * w
    gui_ce = F.cross_entropy(out["gui_logits"].transpose(1, 2), batch["gui_action"], reduction="none")
    loss_gui = (gui_ce * gui_mask).sum() / (gui_mask.sum() + 1e-6)

    # Auxiliary danger + HP-delta prediction loss
    loss_aux = torch.tensor(0.0, device=loss_move.device)
    if "aux" in out:
        aux_pred = out["aux"]
        aux_tgt = batch["aux_target"]
        loss_danger = F.binary_cross_entropy_with_logits(aux_pred[..., 0], aux_tgt[..., 0])
        loss_hp = F.mse_loss(torch.tanh(aux_pred[..., 1]), aux_tgt[..., 1])
        loss_aux = 0.2 * (loss_danger + loss_hp)

    total = (
        1.2 * loss_move
        + 1.0 * loss_yaw
        + 1.0 * loss_pitch
        + 1.0 * loss_left
        + 1.0 * loss_right
        + 0.7 * loss_hotbar
        + 1.5 * loss_gui
        + loss_aux
    )
    metrics = {
        "total": float(total.item()),
        "move": float(loss_move.item()),
        "aim": float((loss_yaw + loss_pitch).item()),
        "combat": float((loss_left + loss_right).item()),
        "gui": float(loss_gui.item()),
    }
    return total, metrics


def main() -> None:
    parser = argparse.ArgumentParser(description="Train 5ifthNN via Behavior Cloning / HG-DAgger")
    parser.add_argument("--data_dir", type=str, required=True, help="Folder containing .5nn.gz recordings")
    parser.add_argument("--out", type=str, default="checkpoints/fifthnn_bc.pt")
    parser.add_argument("--resume", type=str, default=None, help="Optional checkpoint to fine-tune (e.g. HG-DAgger)")
    parser.add_argument("--epochs", type=int, default=20)
    parser.add_argument("--batch_size", type=int, default=32)
    parser.add_argument("--seq_len", type=int, default=128)
    parser.add_argument("--lr", type=float, default=3e-4)
    parser.add_argument("--seed", type=int, default=42)
    args = parser.parse_args()

    random.seed(args.seed)
    np.random.seed(args.seed)
    torch.manual_seed(args.seed)

    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    files = sorted(glob.glob(str(Path(args.data_dir) / "**/*.5nn*"), recursive=True))
    if not files:
        raise FileNotFoundError(f"No .5nn or .5nn.gz recordings found in {args.data_dir}")

    ds = BedWarsSequenceDataset(files, seq_len=args.seq_len, stride=args.seq_len // 2, mirror_aug=True)
    loader = DataLoader(ds, batch_size=args.batch_size, shuffle=True, num_workers=2, pin_memory=True, drop_last=True)

    model = FifthNNModel(include_aux=True).to(device)
    if args.resume:
        ckpt = torch.load(args.resume, map_location=device)
        model.load_state_dict(ckpt["model_state_dict"], strict=False)
        print(f"Resumed weights from {args.resume}")

    print(f"5ifthNN BC Trainer | Device: {device} | Params: {count_parameters(model):,} | Windows: {len(ds)} from {len(files)} files")

    opt = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=1e-4)
    sched = torch.optim.lr_scheduler.CosineAnnealingLR(opt, T_max=max(1, args.epochs))
    scaler = torch.amp.GradScaler("cuda", enabled=(device.type == "cuda"))

    Path(args.out).parent.mkdir(parents=True, exist_ok=True)
    best_loss = math.inf

    for epoch in range(1, args.epochs + 1):
        model.train()
        agg = {"total": 0.0, "move": 0.0, "aim": 0.0, "combat": 0.0, "gui": 0.0}
        steps = 0

        for batch in loader:
            batch = {k: v.to(device, non_blocking=True) for k, v in batch.items()}
            b = batch["scalar_obs"].shape[0]
            h_fast, h_slow = model.init_hidden(b, device)

            opt.zero_grad(set_to_none=True)
            with torch.amp.autocast("cuda", enabled=(device.type == "cuda")):
                out, _, _ = model.forward_sequence(
                    batch["voxel_cats"], batch["voxel_flags"], batch["scalar_obs"], h_fast, h_slow
                )
                loss, m = compute_bc_loss(out, batch)

            scaler.scale(loss).backward()
            scaler.unscale_(opt)
            torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
            scaler.step(opt)
            scaler.update()

            for k in agg:
                agg[k] += m[k]
            steps += 1

        sched.step()
        avg = {k: v / max(1, steps) for k, v in agg.items()}
        print(f"Epoch {epoch:02d}/{args.epochs} | loss={avg['total']:.4f} move={avg['move']:.4f} aim={avg['aim']:.4f} combat={avg['combat']:.4f} gui={avg['gui']:.4f}")

        if avg["total"] < best_loss:
            best_loss = avg["total"]
            torch.save(
                {
                    "schema_version": 1,
                    "epoch": epoch,
                    "best_loss": best_loss,
                    "model_state_dict": model.state_dict(),
                },
                args.out,
            )

    print(f"Saved best BC checkpoint to {args.out} (loss={best_loss:.4f})")


if __name__ == "__main__":
    main()
