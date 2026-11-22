"""
Phase 2A: Behavior Cloning (BC) & HG-DAgger Trainer for 5ifthNN (Colab L4 ready, v0.2).

Upgrades in v0.2:
  - Contiguous Segment Splitting: NEVER bridges a 128-tick BPTT window across lobby gaps,
    server teleports, or episode boundaries (prevents GRU hidden-state corruption!).
  - Automatic Episode-Level Train/Validation Split + Per-Head Accuracy Telemetry
    (Move F1, Aim ±1-bin Acc, Combat Acc, GUI Slot Acc).
  - Exact Left-Right Mirror Augmentation (2x free dataset!).
  - Event Oversampling (Shop 4x, Bed-break 5x, Combat 2x, DAgger takeover 3x).
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
import torch.nn.functional as F
from torch.utils.data import DataLoader, Dataset

from .audit import split_contiguous_segments
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
    """Slices contiguous in-game segments into BPTT windows with mirror augmentation."""

    def __init__(
        self,
        segments: List[Tuple[Dict, List[FrameRecord]]],
        seq_len: int = 128,
        stride: int = 64,
        mirror_aug: bool = True,
    ):
        self.seq_len = seq_len
        self.segments = segments
        self.samples: List[Tuple[int, int, bool, float]] = []

        for seg_idx, (_, frames) in enumerate(segments):
            if len(frames) < seq_len + 2:
                continue
            for start in range(0, len(frames) - seq_len - 1, stride):
                window = frames[start : start + seq_len]
                weight = self._compute_window_weight(window)
                self.samples.append((seg_idx, start, False, weight))
                if mirror_aug:
                    self.samples.append((seg_idx, start, True, weight))

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
        seg_idx, start, mirror, weight = self.samples[idx]
        header, frames = self.segments[seg_idx]
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
    w = batch["sample_weight"].unsqueeze(1)

    loss_move = (F.binary_cross_entropy_with_logits(out["move_logits"], batch["move"], reduction="none").mean(dim=-1) * w).mean()
    loss_yaw = (F.cross_entropy(out["yaw_logits"].transpose(1, 2), batch["yaw_bin"], reduction="none") * w).mean()
    loss_pitch = (F.cross_entropy(out["pitch_logits"].transpose(1, 2), batch["pitch_bin"], reduction="none") * w).mean()
    loss_left = (F.cross_entropy(out["left_logits"].transpose(1, 2), batch["left_state"], reduction="none") * w).mean()
    loss_right = (F.cross_entropy(out["right_logits"].transpose(1, 2), batch["right_state"], reduction="none") * w).mean()
    loss_hotbar = (F.cross_entropy(out["hotbar_logits"].transpose(1, 2), batch["hotbar"], reduction="none") * w).mean()

    gui_mask = batch["gui_open"] * w
    gui_ce = F.cross_entropy(out["gui_logits"].transpose(1, 2), batch["gui_action"], reduction="none")
    loss_gui = (gui_ce * gui_mask).sum() / (gui_mask.sum() + 1e-6)

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

    with torch.no_grad():
        move_pred = (torch.sigmoid(out["move_logits"]) >= 0.5).float()
        move_acc = float((move_pred == batch["move"]).float().mean().item())
        yaw_pred = out["yaw_logits"].argmax(dim=-1)
        aim_close = float(((yaw_pred - batch["yaw_bin"]).abs() <= 1).float().mean().item())
        left_acc = float((out["left_logits"].argmax(dim=-1) == batch["left_state"]).float().mean().item())

    metrics = {
        "total": float(total.item()),
        "move_acc": move_acc,
        "aim_pm1": aim_close,
        "combat_acc": left_acc,
        "gui_loss": float(loss_gui.item()),
    }
    return total, metrics


def load_all_segments(recording_paths: List[str], min_len: int) -> List[Tuple[Dict, List[FrameRecord]]]:
    all_segs: List[Tuple[Dict, List[FrameRecord]]] = []
    for path in recording_paths:
        header, frames = read_recording(path, valid_only=False)
        for seg in split_contiguous_segments(frames):
            if len(seg) >= min_len:
                all_segs.append((header, seg))
    return all_segs


def main() -> None:
    parser = argparse.ArgumentParser(description="Train 5ifthNN via Behavior Cloning / HG-DAgger (v0.2)")
    parser.add_argument("--data_dir", type=str, required=True, help="Folder containing .5nn.gz recordings")
    parser.add_argument("--out", type=str, default="checkpoints/fifthnn_bc.pt")
    parser.add_argument("--resume", type=str, default=None, help="Optional checkpoint to fine-tune (e.g. HG-DAgger)")
    parser.add_argument("--epochs", type=int, default=25)
    parser.add_argument("--batch_size", type=int, default=32)
    parser.add_argument("--seq_len", type=int, default=128)
    parser.add_argument("--val_ratio", type=float, default=0.1)
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

    segments = load_all_segments(files, min_len=args.seq_len + 2)
    if not segments:
        raise RuntimeError(f"No contiguous in-game segments >= {args.seq_len + 2} ticks found.")

    random.shuffle(segments)
    val_count = max(1, int(len(segments) * args.val_ratio)) if len(segments) >= 5 else 0
    val_segs = segments[:val_count]
    train_segs = segments[val_count:]

    train_ds = BedWarsSequenceDataset(train_segs, seq_len=args.seq_len, stride=args.seq_len // 2, mirror_aug=True)
    val_ds = BedWarsSequenceDataset(val_segs, seq_len=args.seq_len, stride=args.seq_len, mirror_aug=False) if val_segs else None

    train_loader = DataLoader(train_ds, batch_size=args.batch_size, shuffle=True, num_workers=2, pin_memory=True, drop_last=len(train_ds) >= args.batch_size)
    val_loader = DataLoader(val_ds, batch_size=args.batch_size, shuffle=False, num_workers=2, pin_memory=True) if val_ds and len(val_ds) > 0 else None

    model = FifthNNModel(include_aux=True).to(device)
    if args.resume:
        ckpt = torch.load(args.resume, map_location=device)
        model.load_state_dict(ckpt["model_state_dict"], strict=False)
        print(f"Resumed weights from {args.resume}")

    print(
        f"5ifthNN BC Trainer v0.2 | Device={device} | Params={count_parameters(model):,} | "
        f"TrainWin={len(train_ds)} ValWin={len(val_ds) if val_ds else 0} | Segments={len(segments)} from {len(files)} files"
    )

    opt = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=1e-4)
    sched = torch.optim.lr_scheduler.CosineAnnealingLR(opt, T_max=max(1, args.epochs))
    scaler = torch.amp.GradScaler("cuda", enabled=(device.type == "cuda"))

    Path(args.out).parent.mkdir(parents=True, exist_ok=True)
    best_metric = math.inf

    for epoch in range(1, args.epochs + 1):
        model.train()
        agg = {"total": 0.0, "move_acc": 0.0, "aim_pm1": 0.0, "combat_acc": 0.0, "gui_loss": 0.0}
        steps = 0

        for batch in train_loader:
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
        tr = {k: v / max(1, steps) for k, v in agg.items()}

        val_str = ""
        track_loss = tr["total"]
        if val_loader is not None:
            model.eval()
            v_agg = {"total": 0.0, "move_acc": 0.0, "aim_pm1": 0.0}
            v_steps = 0
            with torch.no_grad():
                for vb in val_loader:
                    vb = {k: v.to(device, non_blocking=True) for k, v in vb.items()}
                    hf, hs = model.init_hidden(vb["scalar_obs"].shape[0], device)
                    vout, _, _ = model.forward_sequence(vb["voxel_cats"], vb["voxel_flags"], vb["scalar_obs"], hf, hs)
                    _, vm = compute_bc_loss(vout, vb)
                    for k in v_agg:
                        v_agg[k] += vm[k]
                    v_steps += 1
            va = {k: v / max(1, v_steps) for k, v in v_agg.items()}
            track_loss = va["total"]
            val_str = f" || VAL loss={va['total']:.4f} move={va['move_acc']*100:.1f}% aim±1={va['aim_pm1']*100:.1f}%"

        print(
            f"Epoch {epoch:02d}/{args.epochs} | TR loss={tr['total']:.4f} "
            f"move={tr['move_acc']*100:.1f}% aim±1={tr['aim_pm1']*100:.1f}% "
            f"cmb={tr['combat_acc']*100:.1f}% gui={tr['gui_loss']:.3f}{val_str}"
        )

        if track_loss < best_metric:
            best_metric = track_loss
            torch.save(
                {
                    "schema_version": 2,
                    "epoch": epoch,
                    "best_loss": best_metric,
                    "model_state_dict": model.state_dict(),
                },
                args.out,
            )

    print(f"Saved best BC checkpoint to {args.out} (best_loss={best_metric:.4f})")


if __name__ == "__main__":
    main()
