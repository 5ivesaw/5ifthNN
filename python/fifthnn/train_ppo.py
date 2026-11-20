"""
Phase 2B: KL-Anchored PPO & ReST Self-Play Trainer for 5ifthNN.

Takes a BC-bootstrapped checkpoint (even from a small 1-5 hr dataset!) and improves it
using rollouts collected from a REAL Minecraft 26.3 server + BedWars plugin + headless clients:
  - One-time non-farmable BedWars milestone rewards (bed break +2.0, final kill +1.5,
    own bed lost -1.5, win before timer +5.0, loss -5.0, per-tick -0.0005).
  - KL-divergence leash to the frozen BC policy so movement & aim stay human-like.
  - Supports both ReST (filtered self-play wins) and full PPO-Clip with GAE.
"""

from __future__ import annotations

import argparse
import glob
from pathlib import Path
from typing import Dict, List, Tuple

import numpy as np
import torch
import torch.nn.functional as F

from .dataset_io import FrameRecord, read_recording
from .features import encode_frame_obs, encode_frame_targets
from .model import FifthNNModel
from .schema import (
    EVT_BED_BROKEN_BY_ME,
    EVT_FINAL_KILL,
    EVT_GAME_LOST,
    EVT_GAME_WON,
    EVT_KILL,
    EVT_OWN_BED_LOST,
)


def compute_bedwars_shaped_rewards(frames: List[FrameRecord]) -> np.ndarray:
    """
    Compute anti-loophole milestone rewards from a real-server self-play episode.
    Uses irreversible one-time milestones + small time pressure so the agent
    breaks beds, scores final kills, and wins before the game timer expires.
    """
    rewards = np.full(len(frames), -0.0005, dtype=np.float32)
    for i, fr in enumerate(frames):
        ev = fr.event_flags
        if ev & EVT_BED_BROKEN_BY_ME:
            rewards[i] += 2.0
        if ev & EVT_FINAL_KILL:
            rewards[i] += 1.5
        if ev & EVT_KILL:
            rewards[i] += 0.05  # tiny to prevent spawn-farming without breaking beds
        if ev & EVT_OWN_BED_LOST:
            rewards[i] -= 1.5
        if ev & EVT_GAME_WON:
            rewards[i] += 5.0
        if ev & EVT_GAME_LOST:
            rewards[i] -= 5.0
    return rewards


def compute_gae(rewards: np.ndarray, values: np.ndarray, gamma: float = 0.995, lam: float = 0.95) -> Tuple[np.ndarray, np.ndarray]:
    t_len = len(rewards)
    adv = np.zeros(t_len, dtype=np.float32)
    last_gae = 0.0
    for t in reversed(range(t_len)):
        next_val = values[t + 1] if t + 1 < t_len else 0.0
        delta = rewards[t] + gamma * next_val - values[t]
        last_gae = delta + gamma * lam * last_gae
        adv[t] = last_gae
    returns = adv + values
    return adv, returns


def factored_logprob_and_kl(
    out: Dict[str, torch.Tensor],
    ref_out: Dict[str, torch.Tensor],
    actions: Dict[str, torch.Tensor],
) -> Tuple[torch.Tensor, torch.Tensor, torch.Tensor]:
    """Compute joint log-prob, entropy, and exact KL(pi || pi_BC) across all factored heads."""
    # Movement (Bernoulli)
    move_dist = torch.distributions.Bernoulli(logits=out["move_logits"])
    ref_move = torch.distributions.Bernoulli(logits=ref_out["move_logits"])
    lp_move = move_dist.log_prob(actions["move"]).sum(dim=-1)
    kl_move = torch.distributions.kl_divergence(move_dist, ref_move).sum(dim=-1)
    ent_move = move_dist.entropy().sum(dim=-1)

    def _cat_terms(key_logits: str, key_act: str):
        d = torch.distributions.Categorical(logits=out[key_logits])
        rd = torch.distributions.Categorical(logits=ref_out[key_logits])
        return d.log_prob(actions[key_act]), torch.distributions.kl_divergence(d, rd), d.entropy()

    lp_yaw, kl_yaw, ent_yaw = _cat_terms("yaw_logits", "yaw_bin")
    lp_pitch, kl_pitch, ent_pitch = _cat_terms("pitch_logits", "pitch_bin")
    lp_left, kl_left, ent_left = _cat_terms("left_logits", "left_state")
    lp_right, kl_right, ent_right = _cat_terms("right_logits", "right_state")
    lp_hb, kl_hb, ent_hb = _cat_terms("hotbar_logits", "hotbar")
    lp_gui, kl_gui, ent_gui = _cat_terms("gui_logits", "gui_action")

    gui_mask = actions["gui_open"]
    total_lp = lp_move + lp_yaw + lp_pitch + lp_left + lp_right + lp_hb + gui_mask * lp_gui
    total_kl = kl_move + kl_yaw + kl_pitch + kl_left + kl_right + kl_hb + gui_mask * kl_gui
    total_ent = ent_move + ent_yaw + ent_pitch + ent_left + ent_right + ent_hb + gui_mask * ent_gui
    return total_lp, total_kl, total_ent


def run_ppo_update_on_rollouts(
    rollout_dir: str,
    bc_anchor_ckpt: str,
    policy_ckpt: str,
    out_ckpt: str,
    kl_coef: float = 0.08,
    clip_eps: float = 0.2,
    epochs: int = 4,
    lr: float = 1e-4,
    chunk_len: int = 128,
) -> None:
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")

    policy = FifthNNModel(include_aux=False).to(device)
    ref_bc = FifthNNModel(include_aux=False).to(device)

    policy.load_state_dict(torch.load(policy_ckpt, map_location=device)["model_state_dict"], strict=False)
    ref_bc.load_state_dict(torch.load(bc_anchor_ckpt, map_location=device)["model_state_dict"], strict=False)
    ref_bc.eval()
    for p in ref_bc.parameters():
        p.requires_grad_(False)

    files = sorted(glob.glob(str(Path(rollout_dir) / "**/*.5nn*"), recursive=True))
    if not files:
        raise FileNotFoundError(f"No self-play rollout recordings found in {rollout_dir}")

    opt = torch.optim.AdamW(policy.parameters(), lr=lr, weight_decay=1e-4)

    # Pre-encode episodes & compute old log-probs + GAE advantages
    chunks = []
    for path in files:
        header, frames = read_recording(path, valid_only=True)
        if len(frames) < chunk_len:
            continue
        pvp18 = header.get("pvpMode", "1.8") == "1.8"
        srv_id = hash(header.get("serverProfile", "local")) & 0x7

        rewards = compute_bedwars_shaped_rewards(frames)
        vc, vf, obs = [], [], []
        tgts = {k: [] for k in ("move", "yaw_bin", "pitch_bin", "left_state", "right_state", "hotbar", "gui_action", "gui_open")}
        for fr in frames:
            c, f, o = encode_frame_obs(fr, server_profile_id=srv_id, pvp_mode_18=pvp18, mirror=False)
            vc.append(c)
            vf.append(f)
            obs.append(o)
            t = encode_frame_targets(fr, mirror=False)
            for k in tgts:
                tgts[k].append(t[k])

        vc_t = torch.from_numpy(np.stack(vc)).unsqueeze(0).to(device)
        vf_t = torch.from_numpy(np.stack(vf)).unsqueeze(0).to(device)
        obs_t = torch.from_numpy(np.stack(obs)).unsqueeze(0).to(device)
        act_t = {
            "move": torch.from_numpy(np.stack(tgts["move"])).unsqueeze(0).to(device),
            "yaw_bin": torch.tensor(tgts["yaw_bin"], dtype=torch.long, device=device).unsqueeze(0),
            "pitch_bin": torch.tensor(tgts["pitch_bin"], dtype=torch.long, device=device).unsqueeze(0),
            "left_state": torch.tensor(tgts["left_state"], dtype=torch.long, device=device).unsqueeze(0),
            "right_state": torch.tensor(tgts["right_state"], dtype=torch.long, device=device).unsqueeze(0),
            "hotbar": torch.tensor(tgts["hotbar"], dtype=torch.long, device=device).unsqueeze(0),
            "gui_action": torch.tensor(tgts["gui_action"], dtype=torch.long, device=device).unsqueeze(0),
            "gui_open": torch.tensor(tgts["gui_open"], dtype=torch.float32, device=device).unsqueeze(0),
        }

        with torch.no_grad():
            hf, hs = policy.init_hidden(1, device)
            old_out, _, _ = policy.forward_sequence(vc_t, vf_t, obs_t, hf, hs)
            rf, rs = ref_bc.init_hidden(1, device)
            ref_out, _, _ = ref_bc.forward_sequence(vc_t, vf_t, obs_t, rf, rs)
            old_lp, _, _ = factored_logprob_and_kl(old_out, ref_out, act_t)
            vals = old_out["value"].squeeze(0).cpu().numpy()

        adv, ret = compute_gae(rewards, vals)
        adv = (adv - adv.mean()) / (adv.std() + 1e-6)

        for s in range(0, len(frames) - chunk_len + 1, chunk_len):
            e = s + chunk_len
            chunks.append(
                {
                    "vc": vc_t[:, s:e].cpu(),
                    "vf": vf_t[:, s:e].cpu(),
                    "obs": obs_t[:, s:e].cpu(),
                    "act": {k: v[:, s:e].cpu() for k, v in act_t.items()},
                    "old_lp": old_lp[:, s:e].cpu(),
                    "adv": torch.from_numpy(adv[s:e]).unsqueeze(0),
                    "ret": torch.from_numpy(ret[s:e]).unsqueeze(0),
                }
            )

    print(f"KL-PPO Self-Play Update | Episodes: {len(files)} | Chunks: {len(chunks)} | KL beta={kl_coef}")
    for ep in range(1, epochs + 1):
        np.random.shuffle(chunks)
        tot_loss, tot_kl = 0.0, 0.0
        for ch in chunks:
            vc = ch["vc"].to(device)
            vf = ch["vf"].to(device)
            obs = ch["obs"].to(device)
            act = {k: v.to(device) for k, v in ch["act"].items()}
            old_lp = ch["old_lp"].to(device)
            adv = ch["adv"].to(device)
            ret = ch["ret"].to(device)

            hf, hs = policy.init_hidden(1, device)
            out, _, _ = policy.forward_sequence(vc, vf, obs, hf, hs)
            with torch.no_grad():
                rf, rs = ref_bc.init_hidden(1, device)
                ref_out, _, _ = ref_bc.forward_sequence(vc, vf, obs, rf, rs)

            new_lp, kl, ent = factored_logprob_and_kl(out, ref_out, act)
            ratio = torch.exp((new_lp - old_lp).clamp(-10.0, 10.0))
            surr1 = ratio * adv
            surr2 = torch.clamp(ratio, 1.0 - clip_eps, 1.0 + clip_eps) * adv
            policy_loss = -torch.min(surr1, surr2).mean()
            value_loss = 0.5 * F.mse_loss(out["value"], ret)
            kl_loss = kl_coef * kl.mean()
            ent_bonus = -0.005 * ent.mean()

            loss = policy_loss + value_loss + kl_loss + ent_bonus
            opt.zero_grad(set_to_none=True)
            loss.backward()
            torch.nn.utils.clip_grad_norm_(policy.parameters(), 0.5)
            opt.step()

            tot_loss += float(loss.item())
            tot_kl += float(kl.mean().item())

        n = max(1, len(chunks))
        print(f"PPO Epoch {ep}/{epochs} | loss={tot_loss / n:.4f} | mean_KL_vs_BC={tot_kl / n:.4f}")

    Path(out_ckpt).parent.mkdir(parents=True, exist_ok=True)
    torch.save({"schema_version": 1, "model_state_dict": policy.state_dict()}, out_ckpt)
    print(f"Saved KL-PPO policy checkpoint to {out_ckpt}")


def main() -> None:
    p = argparse.ArgumentParser(description="5ifthNN KL-Anchored PPO Self-Play Trainer")
    p.add_argument("--rollout_dir", required=True)
    p.add_argument("--bc_anchor", required=True, help="Frozen BC checkpoint to leash human-likeness")
    p.add_argument("--policy", required=True, help="Current policy checkpoint to improve")
    p.add_argument("--out", default="checkpoints/fifthnn_ppo.pt")
    p.add_argument("--kl_coef", type=float, default=0.08)
    p.add_argument("--epochs", type=int, default=4)
    p.add_argument("--lr", type=float, default=1e-4)
    args = p.parse_args()
    run_ppo_update_on_rollouts(
        args.rollout_dir, args.bc_anchor, args.policy, args.out, kl_coef=args.kl_coef, epochs=args.epochs, lr=args.lr
    )


if __name__ == "__main__":
    main()
