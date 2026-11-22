"""PyTorch verification tests for v0.3: obs(t)->act(t+1) causal parity, burn-in, and 4x 0ms aux heads."""

from __future__ import annotations

import tempfile
from pathlib import Path

import torch

from fifthnn.dataset_io import read_recording
from fifthnn.features import encode_frame_obs, encode_frame_targets
from fifthnn.model import FifthNNModel, count_parameters
from fifthnn.schema import KEY_A, KEY_W, QFLAG_VALID_IN_GAME
from fifthnn.train_bc import BedWarsSequenceDataset, compute_bc_loss, split_segments_by_episode
from fifthnn.train_ppo import factored_logprob_and_kl
from tests.test_schema_and_io import _write_synthetic_recording


def test_causal_shift_burnin_and_aux_training() -> None:
    with tempfile.TemporaryDirectory() as td:
        p = Path(td) / "causal.5nn.gz"
        # 80 contiguous frames where ONLY even ticks press KEY_W and odd ticks press KEY_A
        specs = [(t, 1, QFLAG_VALID_IN_GAME) for t in range(80)]
        _write_synthetic_recording(
            p,
            specs,
            extra_kwargs_fn=lambda i, t: {
                "key_mask": KEY_W if (i % 2 == 0) else KEY_A,
                "left_clicks": 1 if (i % 2 == 1) else 0,
            },
        )
        hdr, frames = read_recording(p, valid_only=True)
        ds = BedWarsSequenceDataset(
            [(hdr, frames, [])],
            seq_len=32,
            stride=32,
            burn_in=8,
            mirror_aug=False,
            hotbar_perm_aug=False,
            aug_noise=False,
        )
        assert len(ds) >= 1
        sample = ds[0]

        # 1. Verify obs(0) pairs with act(1) (odd tick -> KEY_A pressed, KEY_W not pressed!)
        assert sample["move"][0, 0].item() == 0.0 and sample["move"][0, 1].item() == 1.0
        # 2. Verify burn-in masks first 8 ticks to 0.0 and activates ticks 8..31
        assert sample["tick_weight"][:8].sum().item() == 0.0
        assert sample["tick_weight"][8:].min().item() > 0.0

        # 3. Verify 4 aux heads train cleanly & inference model strips them cleanly
        model = FifthNNModel(include_aux=True, dropout=0.1)
        batch = {k: v.unsqueeze(0) for k, v in sample.items()}
        hf, hs = model.init_hidden(1, torch.device("cpu"))
        out, _, _ = model.forward_sequence(batch["voxel_cats"], batch["voxel_flags"], batch["scalar_obs"], hf, hs)
        assert "aux_void_1s" in out and "aux_next_kin" in out and "aux_enemy_fut" in out and "aux_macro_intent" in out

        loss, metrics = compute_bc_loss(out, batch)
        assert torch.isfinite(loss)
        loss.backward()
        assert metrics["aux_loss"] >= 0.0

        infer_model = FifthNNModel(include_aux=False)
        assert count_parameters(infer_model) == 1_787_330


def test_close_gui_supervision_and_episode_split() -> None:
    with tempfile.TemporaryDirectory() as td:
        p = Path(td) / "gui_close.5nn.gz"
        # Shop open on ticks 0..14, closed at tick 15 (gui_type=0, gui_click_slot=-2 -> gui_action=1!)
        specs = [(t, 1, QFLAG_VALID_IN_GAME) for t in range(80)]
        _write_synthetic_recording(
            p,
            specs,
            extra_kwargs_fn=lambda i, t: {
                "gui_type": 2 if i <= 14 else 0,
                "gui_click_slot": -2 if i == 15 else -1,
            },
        )
        hdr, frames = read_recording(p, valid_only=True)
        ds = BedWarsSequenceDataset(
            [(hdr, frames, [])],
            seq_len=32,
            stride=32,
            burn_in=4,
            mirror_aug=False,
            hotbar_perm_aug=False,
            aug_noise=False,
        )
        sample = ds[0]
        # At step 14: fr_obs=frames[14] (gui_type=2), fr_act=frames[15] (gui_click_slot=-2 -> gui_action=1)
        assert sample["gui_action"][14].item() == 1
        assert sample["gui_open"][14].item() == 1.0, "close_gui label was masked out!"
        # At step 15: fr_obs=frames[15] (gui_type=0) -> gui_open must be 0.0
        assert sample["gui_open"][15].item() == 0.0

        # Verify episode-level validation split never splits the same episode across train & val
        segs = [
            ({"_source_file": "a.5nn.gz"}, frames[:40], []),
            ({"_source_file": "a.5nn.gz"}, frames[40:], []),
            ({"_source_file": "b.5nn.gz"}, frames[:40], []),
        ]
        tr, va = split_segments_by_episode(segs, val_ratio=0.5)
        tr_keys = {(h["_source_file"], s[0].episode_id) for h, s, _ in tr}
        va_keys = {(h["_source_file"], s[0].episode_id) for h, s, _ in va}
        assert tr_keys.isdisjoint(va_keys), f"Episode leaked across train/val: {tr_keys & va_keys}"


def test_ppo_chunk_hidden_state_ratio_unity() -> None:
    torch.manual_seed(0)
    policy = FifthNNModel(include_aux=False, dropout=0.0)
    ref_bc = FifthNNModel(include_aux=False, dropout=0.0)
    ref_bc.load_state_dict(policy.state_dict())

    with tempfile.TemporaryDirectory() as td:
        p = Path(td) / "ppo_chunk.5nn.gz"
        specs = [(t, 1, QFLAG_VALID_IN_GAME) for t in range(41)]
        _write_synthetic_recording(p, specs)
        _, frames = read_recording(p, valid_only=True)

        vc, vf, obs = [], [], []
        tgts = {k: [] for k in ("move", "yaw_bin", "pitch_bin", "left_state", "right_state", "hotbar", "gui_action", "gui_open")}
        for ti in range(40):
            c, f, o = encode_frame_obs(frames[ti])
            vc.append(c)
            vf.append(f)
            obs.append(o)
            t = encode_frame_targets(frames[ti + 1])
            for k in tgts:
                tgts[k].append((1 if frames[ti].gui_type > 0 else 0) if k == "gui_open" else t[k])

        import numpy as np

        vc_t = torch.from_numpy(np.stack(vc)).unsqueeze(0)
        vf_t = torch.from_numpy(np.stack(vf)).unsqueeze(0)
        obs_t = torch.from_numpy(np.stack(obs)).unsqueeze(0)
        act_t = {
            "move": torch.from_numpy(np.stack(tgts["move"])).unsqueeze(0),
            "yaw_bin": torch.tensor(tgts["yaw_bin"], dtype=torch.long).unsqueeze(0),
            "pitch_bin": torch.tensor(tgts["pitch_bin"], dtype=torch.long).unsqueeze(0),
            "left_state": torch.tensor(tgts["left_state"], dtype=torch.long).unsqueeze(0),
            "right_state": torch.tensor(tgts["right_state"], dtype=torch.long).unsqueeze(0),
            "hotbar": torch.tensor(tgts["hotbar"], dtype=torch.long).unsqueeze(0),
            "gui_action": torch.tensor(tgts["gui_action"], dtype=torch.long).unsqueeze(0),
            "gui_open": torch.tensor(tgts["gui_open"], dtype=torch.float32).unsqueeze(0),
        }

        chunk_len = 20
        hf, hs = policy.init_hidden(1, torch.device("cpu"))
        rf, rs = ref_bc.init_hidden(1, torch.device("cpu"))
        saved = []
        with torch.no_grad():
            for s in (0, 20):
                e = s + chunk_len
                hf_in, hs_in = hf.clone(), hs.clone()
                rf_in, rs_in = rf.clone(), rs.clone()
                old_out, hf, hs = policy.forward_sequence(vc_t[:, s:e], vf_t[:, s:e], obs_t[:, s:e], hf_in, hs_in, start_step=s)
                ref_out, rf, rs = ref_bc.forward_sequence(vc_t[:, s:e], vf_t[:, s:e], obs_t[:, s:e], rf_in, rs_in, start_step=s)
                old_lp, _, _ = factored_logprob_and_kl(old_out, ref_out, {k: v[:, s:e] for k, v in act_t.items()})
                saved.append((s, e, hf_in, hs_in, rf_in, rs_in, old_lp))

        for s, e, hf_in, hs_in, rf_in, rs_in, old_lp in saved:
            out, _, _ = policy.forward_sequence(vc_t[:, s:e], vf_t[:, s:e], obs_t[:, s:e], hf_in, hs_in, start_step=s)
            ref_out, _, _ = ref_bc.forward_sequence(vc_t[:, s:e], vf_t[:, s:e], obs_t[:, s:e], rf_in, rs_in, start_step=s)
            new_lp, _, _ = factored_logprob_and_kl(out, ref_out, {k: v[:, s:e] for k, v in act_t.items()})
            max_diff = float(torch.max(torch.abs(new_lp - old_lp)).item())
            assert max_diff < 1e-5, f"PPO chunk {s}:{e} ratio mismatch! max_diff={max_diff}"


if __name__ == "__main__":
    test_causal_shift_burnin_and_aux_training()
    test_close_gui_supervision_and_episode_split()
    test_ppo_chunk_hidden_state_ratio_unity()
    print("ALL 5ifthNN v0.4.1 PyTorch causal-parity, close_gui, PPO-chunk & 0ms-aux tests PASSED!")
