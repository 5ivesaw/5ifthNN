"""PyTorch verification tests for v0.3: obs(t)->act(t+1) causal parity, burn-in, and 4x 0ms aux heads."""

from __future__ import annotations

import tempfile
from pathlib import Path

import torch

from fifthnn.dataset_io import read_recording
from fifthnn.model import FifthNNModel, count_parameters
from fifthnn.schema import KEY_A, KEY_W, QFLAG_VALID_IN_GAME
from fifthnn.train_bc import BedWarsSequenceDataset, compute_bc_loss
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


if __name__ == "__main__":
    test_causal_shift_burnin_and_aux_training()
    print("ALL 5ifthNN v0.3 PyTorch causal-parity, burn-in & 0ms-aux tests PASSED!")
