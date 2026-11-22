"""
Phase 2A: Behavior Cloning (BC) & HG-DAgger Trainer for 5ifthNN (Colab L4 ready, v0.3).

Upgrades in v0.3:
  - 1-Tick Causal Parity Fix: obs(t) -> act(t+1) eliminates END_CLIENT_TICK future leakage!
  - GRU Burn-In Prefix (16 ticks zero-loss warmup) eliminates boundary artifacts after cuts.
  - 3-Way Void Masking: masks final 25 ticks of accidental slips while KEEPING Tactical Voids
    (never triggers on pitch > 50° alone!) and oversampling Edge Recoveries 6x.
  - ToggleSprint Normalization + GUI-Safe 0..8 Hotbar Permutation (9! invariance on closed-GUI windows).
  - Idle Downsampling (0.2x), AFK-Shop Masking (>80t no click), Active-Mining 3x, F6 Highlights 2.5x.
  - 4 Training-Only 0ms Auxiliary Heads (void-in-1s, next kinematics, enemy t+10, Slow-GRU macro intent).
  - Early Stopping + Episode-Level Validation Split.
"""

from __future__ import annotations

import argparse
import math
import random
from pathlib import Path
from typing import Dict, List, Set, Tuple

import numpy as np
import torch
import torch.nn.functional as F
from torch.utils.data import DataLoader, Dataset

from .audit import classify_void_falls, detect_edge_recoveries, split_contiguous_segments
from .dataset_io import FrameRecord, find_recordings, load_tags_sidecar, read_recording
from .features import _world_to_ego, encode_frame_obs, encode_frame_targets
from .model import FifthNNModel, count_parameters
from .schema import (
    EVT_BED_BROKEN_BY_ME,
    EVT_DAMAGE_DEALT,
    EVT_DAMAGE_TAKEN,
    EVT_ITEM_BOUGHT,
    QFLAG_DAGGER_TAKEOVER,
)

SFLAG_ON_GROUND = 1 << 0


def _compute_macro_intent_label(fr_now: FrameRecord, fr_future: FrameRecord) -> int:
    """
    Determine which landmark category (0=Shop, 1=Gen, 2=OwnBed/Home, 3=EnemyBed)
    the player moved closest toward over the next ~40 ticks (supervises Slow GRU @ 2Hz).
    Returns -1 if no landmarks are present.
    """
    best_cls = -1
    best_gain = 0.25  # require at least 0.25b net approach
    for lm in fr_now.landmarks:
        ltype = lm[0]
        if ltype in (5, 6):
            cls = 0  # Shop / Upgrade NPC
        elif ltype in (3, 4):
            cls = 1  # Diamond / Emerald Gen
        elif ltype in (0, 2):
            cls = 2  # Own Bed / Home
        elif ltype == 1 and lm[5] > 0:
            cls = 3  # Living Enemy Bed
        else:
            continue
        d0 = math.sqrt((lm[2] - fr_now.x) ** 2 + (lm[4] - fr_now.z) ** 2)
        d1 = math.sqrt((lm[2] - fr_future.x) ** 2 + (lm[4] - fr_future.z) ** 2)
        gain = d0 - d1
        if gain > best_gain:
            best_gain = gain
            best_cls = cls
    return best_cls


class BedWarsSequenceDataset(Dataset):
    """
    Slices contiguous in-game segments into causally aligned `obs(t) -> act(t+1)` BPTT windows
    with mirror augmentation, GUI-safe hotbar permutation, burn-in, failure-tail masking,
    recovery oversampling, and 4 training-only auxiliary targets.
    """

    def __init__(
        self,
        segments: List[Tuple[Dict, List[FrameRecord], List[Dict]]],
        seq_len: int = 128,
        stride: int = 64,
        burn_in: int = 16,
        mirror_aug: bool = True,
        hotbar_perm_aug: bool = True,
        aug_noise: bool = True,
    ):
        self.seq_len = seq_len
        self.burn_in = min(burn_in, max(0, seq_len // 4))
        self.hotbar_perm_aug = hotbar_perm_aug
        self.aug_noise = aug_noise
        self.segments = segments
        self.seg_meta: List[Dict[str, Set[int]]] = []
        self.samples: List[Tuple[int, int, bool, float]] = []

        for seg_idx, (_, frames, tags) in enumerate(segments):
            vf_info = classify_void_falls(frames)
            rec_indices = detect_edge_recoveries(frames)
            hl_ranges = [
                (int(tg.get("startTick", -1)), int(tg.get("endTick", -1)))
                for tg in tags
                if tg.get("tag") == "USER_HIGHLIGHT_8S"
            ]
            hl_indices = {
                i for i, fr in enumerate(frames) if any(lo <= fr.tick_index <= hi for lo, hi in hl_ranges)
            }
            self.seg_meta.append(
                {
                    "slip_mask": vf_info["mask_indices"],
                    "tactical_void": vf_info["tactical_indices"],
                    "recoveries": rec_indices,
                    "highlights": hl_indices,
                }
            )

            # Need seq_len + 21 so t+1 (causal action) and t+20 (void aux) are always in bounds
            if len(frames) < seq_len + 22:
                continue
            for start in range(0, len(frames) - seq_len - 21, stride):
                window = frames[start : start + seq_len + 1]
                weight = self._compute_window_weight(window, start, self.seg_meta[seg_idx])
                self.samples.append((seg_idx, start, False, weight))
                if mirror_aug:
                    self.samples.append((seg_idx, start, True, weight))

    @staticmethod
    def _compute_window_weight(window: List[FrameRecord], start: int, meta: Dict[str, Set[int]]) -> float:
        w = 1.0
        end = start + len(window)
        if any(start <= idx < end for idx in meta["recoveries"]):
            w = max(w, 6.0)
        if any(fr.event_flags & EVT_BED_BROKEN_BY_ME for fr in window):
            w = max(w, 5.0)
        if any(fr.gui_type > 0 or fr.gui_click_slot >= 0 or (fr.event_flags & EVT_ITEM_BOUGHT) for fr in window):
            w = max(w, 4.0)
        if any(fr.mining_progress > 0.05 for fr in window):
            w = max(w, 3.0)
        if any(fr.quality_flags & QFLAG_DAGGER_TAKEOVER for fr in window):
            w = max(w, 3.0)
        if any(start <= idx < end for idx in meta["highlights"]):
            w = max(w, 2.5)
        if any(fr.event_flags & (EVT_DAMAGE_DEALT | EVT_DAMAGE_TAKEN) for fr in window):
            w = max(w, 2.0)
        if any(start <= idx < end for idx in meta["tactical_void"]):
            w = max(w, 1.5)
        return w

    def __len__(self) -> int:
        return len(self.samples)

    def __getitem__(self, idx: int) -> Dict[str, torch.Tensor]:
        seg_idx, start, mirror, weight = self.samples[idx]
        header, frames, _ = self.segments[seg_idx]
        meta = self.seg_meta[seg_idx]
        pvp18 = header.get("pvpMode", "1.8") == "1.8"
        srv_id = hash(header.get("serverProfile", "local")) & 0x7

        # Critical Invariant #2: Only permute 0..8 hotbar slots if GUI is closed across the ENTIRE window!
        window_slice = frames[start : start + self.seq_len + 1]
        gui_always_closed = all(fr.gui_type == 0 for fr in window_slice)
        rng = np.random.default_rng()
        hotbar_perm = rng.permutation(9).tolist() if (self.hotbar_perm_aug and gui_always_closed) else None

        vc_list, vf_list, obs_list = [], [], []
        move_list, yaw_list, pitch_list = [], [], []
        left_list, right_list, hotbar_list, gui_list, gui_open_list = [], [], [], [], []
        tick_weight_list = []
        aux_void_list, aux_kin_list, aux_enemy_list, aux_enemy_mask_list, aux_macro_list = [], [], [], [], []

        idle_run = 0
        shop_no_click_run = 0

        for step_i in range(self.seq_len):
            t = start + step_i
            fr_obs = frames[t]
            # 1-TICK CAUSAL PARITY FIX: predict act(t + 1) from obs(t)!
            fr_act = frames[t + 1]
            fr_20 = frames[min(t + 20, len(frames) - 1)]
            fr_10 = frames[min(t + 10, len(frames) - 1)]
            fr_40 = frames[min(t + 40, len(frames) - 1)]

            vc, vf, obs = encode_frame_obs(
                fr_obs,
                server_profile_id=srv_id,
                pvp_mode_18=pvp18,
                mirror=mirror,
                hotbar_perm=hotbar_perm,
                aug_noise=self.aug_noise,
                rng=rng if self.aug_noise else None,
            )
            tgt = encode_frame_targets(fr_act, mirror=mirror, hotbar_perm=hotbar_perm)

            # Per-tick loss weighting: GRU burn-in + accidental-slip mask + idle downsampling
            if step_i < self.burn_in or (t in meta["slip_mask"]) or ((t + 1) in meta["slip_mask"]):
                tw = 0.0
            else:
                tw = 1.0
                if fr_act.key_mask == 0 and abs(fr_act.delta_yaw) < 0.05 and abs(fr_act.delta_pitch) < 0.05:
                    idle_run += 1
                    if idle_run > 30:
                        tw = 0.2
                else:
                    idle_run = 0

            # AFK-shop masking (>80 consecutive open-shop ticks with zero clicks)
            gui_open_val = float(tgt["gui_open"]) if fr_obs.gui_type > 0 else 0.0
            if gui_open_val > 0.0:
                if fr_act.gui_click_slot >= 0:
                    shop_no_click_run = 0
                else:
                    shop_no_click_run += 1
                    if shop_no_click_run > 80:
                        gui_open_val = 0.0
            else:
                shop_no_click_run = 0

            # Aux 1: Void / fall > 4b within 1s (20 ticks)
            void_1s = 1.0 if (fr_20.y < fr_obs.y - 4.0 or fr_20.y < 10.0) else 0.0

            # Aux 2: Next-tick egocentric displacement (dx, dy, dz) + next onGround
            sign_r = -1.0 if mirror else 1.0
            nfwd, nright = _world_to_ego(fr_act.x - fr_obs.x, fr_act.z - fr_obs.z, fr_obs.yaw)
            nright *= sign_r
            ndy = max(-2.0, min(2.0, fr_act.y - fr_obs.y))
            next_onground = 1.0 if (fr_act.self_flags & SFLAG_ON_GROUND) else 0.0

            # Aux 3: Closest enemy future relative (fwd, right, dy) at t+10 (masked when not visible)
            efut = [0.0, 0.0, 0.0]
            emask = 0.0
            if fr_obs.players and fr_10.players:
                eid = fr_obs.players[0][0]
                match_e = next((p for p in fr_10.players if p[0] == eid), None)
                if match_e is not None:
                    ef, er = _world_to_ego(match_e[4] - fr_obs.x, match_e[6] - fr_obs.z, fr_obs.yaw)
                    er *= sign_r
                    efut = [
                        max(-1.0, min(1.0, ef / 32.0)),
                        max(-1.0, min(1.0, er / 32.0)),
                        max(-1.0, min(1.0, (match_e[5] - fr_obs.y) / 16.0)),
                    ]
                    emask = 1.0

            # Aux 4: 4-way macro intent over next 40 ticks (supervises Slow GRU @ 2Hz)
            macro_cls = _compute_macro_intent_label(fr_obs, fr_40)

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
            gui_open_list.append(gui_open_val)
            tick_weight_list.append(tw)
            aux_void_list.append(void_1s)
            aux_kin_list.append([max(-1.0, min(1.0, nfwd)), max(-1.0, min(1.0, ndy)), max(-1.0, min(1.0, nright)), next_onground])
            aux_enemy_list.append(efut)
            aux_enemy_mask_list.append(emask)
            aux_macro_list.append(macro_cls)

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
            "tick_weight": torch.tensor(tick_weight_list, dtype=torch.float32),
            "aux_void_1s": torch.tensor(aux_void_list, dtype=torch.float32),
            "aux_next_kin": torch.tensor(aux_kin_list, dtype=torch.float32),
            "aux_enemy_fut": torch.tensor(aux_enemy_list, dtype=torch.float32),
            "aux_enemy_mask": torch.tensor(aux_enemy_mask_list, dtype=torch.float32),
            "aux_macro_intent": torch.tensor(aux_macro_list, dtype=torch.long),
            "sample_weight": torch.tensor(weight, dtype=torch.float32),
        }


def compute_bc_loss(out: Dict[str, torch.Tensor], batch: Dict[str, torch.Tensor]) -> Tuple[torch.Tensor, Dict[str, float]]:
    # Combined per-tick weight: window importance * per-tick mask (burn-in=0, slip=0, idle=0.2)
    w = batch["sample_weight"].unsqueeze(1) * batch["tick_weight"]
    w_sum = w.sum() + 1e-6

    def _wmean(per_tick: torch.Tensor) -> torch.Tensor:
        return (per_tick * w).sum() / w_sum

    loss_move = _wmean(F.binary_cross_entropy_with_logits(out["move_logits"], batch["move"], reduction="none").mean(dim=-1))
    loss_yaw = _wmean(F.cross_entropy(out["yaw_logits"].transpose(1, 2), batch["yaw_bin"], reduction="none"))
    loss_pitch = _wmean(F.cross_entropy(out["pitch_logits"].transpose(1, 2), batch["pitch_bin"], reduction="none"))
    loss_left = _wmean(F.cross_entropy(out["left_logits"].transpose(1, 2), batch["left_state"], reduction="none"))
    loss_right = _wmean(F.cross_entropy(out["right_logits"].transpose(1, 2), batch["right_state"], reduction="none"))
    loss_hotbar = _wmean(F.cross_entropy(out["hotbar_logits"].transpose(1, 2), batch["hotbar"], reduction="none"))

    gui_mask = batch["gui_open"] * w
    gui_ce = F.cross_entropy(out["gui_logits"].transpose(1, 2), batch["gui_action"], reduction="none")
    loss_gui = (gui_ce * gui_mask).sum() / (gui_mask.sum() + 1e-6)

    loss_aux = torch.tensor(0.0, device=loss_move.device)
    if "aux_void_1s" in out:
        l_void = _wmean(F.binary_cross_entropy_with_logits(out["aux_void_1s"], batch["aux_void_1s"], reduction="none"))
        kin_pred = out["aux_next_kin"]
        kin_tgt = batch["aux_next_kin"]
        l_kin_xyz = _wmean(F.mse_loss(torch.tanh(kin_pred[..., :3]), kin_tgt[..., :3], reduction="none").mean(dim=-1))
        l_kin_gnd = _wmean(F.binary_cross_entropy_with_logits(kin_pred[..., 3], kin_tgt[..., 3], reduction="none"))

        em = batch["aux_enemy_mask"] * (batch["tick_weight"] > 0).float()
        l_efut = (F.mse_loss(torch.tanh(out["aux_enemy_fut"]), batch["aux_enemy_fut"], reduction="none").mean(dim=-1) * em).sum() / (em.sum() + 1e-6)

        macro_tgt = batch["aux_macro_intent"]
        macro_valid = (macro_tgt >= 0).float() * (batch["tick_weight"] > 0).float()
        macro_safe = macro_tgt.clamp(min=0)
        l_macro = (F.cross_entropy(out["aux_macro_intent"].transpose(1, 2), macro_safe, reduction="none") * macro_valid).sum() / (macro_valid.sum() + 1e-6)

        loss_aux = 0.15 * (l_void + l_kin_xyz + l_kin_gnd + l_efut + 0.5 * l_macro)

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
        active = (batch["tick_weight"] > 0).float()
        a_sum = active.sum() + 1e-6
        move_pred = (torch.sigmoid(out["move_logits"]) >= 0.5).float()
        move_acc = float((((move_pred == batch["move"]).float().mean(dim=-1)) * active).sum().item() / a_sum.item())
        yaw_pred = out["yaw_logits"].argmax(dim=-1)
        aim_close = float(((((yaw_pred - batch["yaw_bin"]).abs() <= 1).float()) * active).sum().item() / a_sum.item())
        left_acc = float((((out["left_logits"].argmax(dim=-1) == batch["left_state"]).float()) * active).sum().item() / a_sum.item())

    metrics = {
        "total": float(total.item()),
        "move_acc": move_acc,
        "aim_pm1": aim_close,
        "combat_acc": left_acc,
        "gui_loss": float(loss_gui.item()),
        "aux_loss": float(loss_aux.item()),
    }
    return total, metrics


def load_all_segments(recording_paths: List[str], min_len: int) -> List[Tuple[Dict, List[FrameRecord], List[Dict]]]:
    all_segs: List[Tuple[Dict, List[FrameRecord], List[Dict]]] = []
    for path in recording_paths:
        header, frames = read_recording(path, valid_only=False)
        tags = load_tags_sidecar(path)
        for seg in split_contiguous_segments(frames, tags=tags):
            if len(seg) >= min_len:
                all_segs.append((header, seg, tags))
    return all_segs


def main() -> None:
    parser = argparse.ArgumentParser(description="Train 5ifthNN via Behavior Cloning / HG-DAgger (v0.3)")
    parser.add_argument("--data_dir", type=str, required=True, help="Folder containing .5nn.gz recordings")
    parser.add_argument("--out", type=str, default="checkpoints/fifthnn_bc.pt")
    parser.add_argument("--resume", type=str, default=None, help="Optional checkpoint to fine-tune (e.g. HG-DAgger)")
    parser.add_argument("--epochs", type=int, default=25)
    parser.add_argument("--batch_size", type=int, default=32)
    parser.add_argument("--seq_len", type=int, default=128)
    parser.add_argument("--burn_in", type=int, default=16)
    parser.add_argument("--val_ratio", type=float, default=0.1)
    parser.add_argument("--patience", type=int, default=6, help="Early-stopping patience on validation loss")
    parser.add_argument("--lr", type=float, default=3e-4)
    parser.add_argument("--seed", type=int, default=42)
    args = parser.parse_args()

    random.seed(args.seed)
    np.random.seed(args.seed)
    torch.manual_seed(args.seed)

    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    files = find_recordings(args.data_dir)
    if not files:
        raise FileNotFoundError(f"No .5nn or .5nn.gz recordings found in {args.data_dir}")

    segments = load_all_segments(files, min_len=args.seq_len + 22)
    if not segments:
        raise RuntimeError(f"No contiguous in-game segments >= {args.seq_len + 22} ticks found.")

    random.shuffle(segments)
    val_count = max(1, int(len(segments) * args.val_ratio)) if len(segments) >= 5 else 0
    val_segs = segments[:val_count]
    train_segs = segments[val_count:]

    train_ds = BedWarsSequenceDataset(
        train_segs, seq_len=args.seq_len, stride=args.seq_len // 2, burn_in=args.burn_in,
        mirror_aug=True, hotbar_perm_aug=True, aug_noise=True,
    )
    val_ds = (
        BedWarsSequenceDataset(
            val_segs, seq_len=args.seq_len, stride=args.seq_len, burn_in=args.burn_in,
            mirror_aug=False, hotbar_perm_aug=False, aug_noise=False,
        )
        if val_segs
        else None
    )

    train_loader = DataLoader(
        train_ds, batch_size=args.batch_size, shuffle=True, num_workers=2, pin_memory=True,
        drop_last=len(train_ds) >= args.batch_size,
    )
    val_loader = (
        DataLoader(val_ds, batch_size=args.batch_size, shuffle=False, num_workers=2, pin_memory=True)
        if val_ds and len(val_ds) > 0
        else None
    )

    model = FifthNNModel(include_aux=True, dropout=0.1).to(device)
    if args.resume:
        ckpt = torch.load(args.resume, map_location=device)
        model.load_state_dict(ckpt["model_state_dict"], strict=False)
        print(f"Resumed weights from {args.resume}")

    print(
        f"5ifthNN BC Trainer v0.3 (obs(t)->act(t+1)) | Device={device} | Params={count_parameters(model):,} | "
        f"TrainWin={len(train_ds)} ValWin={len(val_ds) if val_ds else 0} | Segments={len(segments)} from {len(files)} files"
    )

    opt = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=1e-4)
    sched = torch.optim.lr_scheduler.CosineAnnealingLR(opt, T_max=max(1, args.epochs))
    scaler = torch.amp.GradScaler("cuda", enabled=(device.type == "cuda"))

    Path(args.out).parent.mkdir(parents=True, exist_ok=True)
    best_metric = math.inf
    no_improve = 0

    for epoch in range(1, args.epochs + 1):
        model.train()
        agg = {"total": 0.0, "move_acc": 0.0, "aim_pm1": 0.0, "combat_acc": 0.0, "gui_loss": 0.0, "aux_loss": 0.0}
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
            v_agg = {"total": 0.0, "move_acc": 0.0, "aim_pm1": 0.0, "combat_acc": 0.0}
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
            val_str = f" || VAL loss={va['total']:.4f} move={va['move_acc']*100:.1f}% aim±1={va['aim_pm1']*100:.1f}% cmb={va['combat_acc']*100:.1f}%"

        print(
            f"Epoch {epoch:02d}/{args.epochs} | TR loss={tr['total']:.4f} "
            f"move={tr['move_acc']*100:.1f}% aim±1={tr['aim_pm1']*100:.1f}% "
            f"cmb={tr['combat_acc']*100:.1f}% aux={tr['aux_loss']:.3f}{val_str}"
        )

        if track_loss < best_metric:
            best_metric = track_loss
            no_improve = 0
            torch.save(
                {
                    "schema_version": 2,
                    "epoch": epoch,
                    "best_loss": best_metric,
                    "model_state_dict": model.state_dict(),
                },
                args.out,
            )
        else:
            no_improve += 1
            if val_loader is not None and args.patience > 0 and no_improve >= args.patience:
                print(f"Early stopping at epoch {epoch} (no val improvement for {args.patience} epochs).")
                break

    print(f"Saved best BC checkpoint to {args.out} (best_loss={best_metric:.4f})")


if __name__ == "__main__":
    main()
