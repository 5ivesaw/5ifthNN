# 5ifthNN

**A lightweight, 100% Pure Neural Network BedWars AI for Minecraft Java 26.3 (1.8-style PvP)** — split into:

1. **Phase 1 — High-Fidelity 1:1 Dataset Recorder (Fabric 26.3 Mod)**:
   Captures a lossless, visual-equivalent structured snapshot of the entire game at **20 Hz + sub-tick click/mouse fidelity** (`13×7×13` 3D voxel world + 32-ray panoramic lidar + all visible players & threats/dropped resources + full 41-slot inventory + full 54-slot open shop GUI + directional sound radar + scoreboard/match events + exact 1.8 PvP inputs) into crash-safe compressed `.5nn.gz` binary files (~12–18 MB/hr).
2. **Phase 2 — Embedded ONNX Runtime Inference Engine + Python Training/Export Suite**:
   Train a **~2.0M-parameter Two-Timescale Pure NN** on Google Colab L4 via Behavior Cloning + HG-DAgger + KL-Anchored PPO Self-Play on real headless Minecraft servers, export to a ~7.5 MB `.onnx` file, drop it into `.minecraft/config/5ifthnn/models/`, and run live in-game at **<2.5 ms/tick on CPU**.

---

## Core Design Decisions

| Decision | Choice | Why |
| :--- | :--- | :--- |
| **Target Version** | **Minecraft Java 26.3** (Fabric `0.20.1`, Fabric API `0.166.2+26.3`, Java 25) | Native unobfuscated Mojang mappings, modern performance, connects cleanly to 1.8-PvP BedWars servers (ViaVersion / OldCombatMechanics) |
| **Model Size** | **~1.8M – 2.1M parameters** (~7.5 MB FP32 ONNX) | Big enough for 3D bridging, 1.8 PvP spacing, shop navigation & multi-minute BedWars memory; small enough to train from a few hours of data + self-play PPO and run in <2.5 ms/tick on any laptop CPU |
| **Architecture** | **Two-Timescale Unified Pure NN** (`SpatialVoxelEncoder` + `Slow GRU 192d @ 2Hz` + `Fast GRU 384d @ 20Hz`) | Solves both 50ms micro-control (W-tap, speed-bridge) AND multi-minute strategic memory in ONE pure neural network |
| **Human Dataset Needed** | **2 – 5 hours** targeted high-density recordings (to bootstrap PPO) or **15 – 25 hours** (for strong BC alone) | Mirror augmentation doubles data 2× automatically; targeted drills cover rare actions (shopping, bed breaking, ledge clutches) 10× denser than random matches |
| **1.8 PvP Fidelity** | Sub-tick `leftClicksThisTick` & `rightClicksThisTick` + `attackSpeedAttr` + `hurtTime` | Preserves 1.8 spam-clicking, W-tapping, block-hitting, and knockback timing on 26.3 clients |
| **Pure NN vs Plumbing** | **0% hardcoded strategy/PvP/bridging**; thin I/O plumbing only | Mod handles egocentric feature encoding, Mu-Law sub-frame camera smoothing, human CPS/turn caps, valid GUI slot masking, and emergency kill-switches |

---

## Recommended Data Diet (Small Dataset → Heavy Colab PPO Path)

If you have limited time for manual recording and lots of Google Colab compute units for self-play PPO:

| Recording Mode (GUI / Command) | Target Time | Purpose |
| :--- | :--- | :--- |
| `DRILL_BRIDGING` | **30–45 min** | Speed-bridging, diagonal bridges, staircases, deliberate near-ledge edge recoveries |
| `DRILL_SHOP` | **30 min** | Fast item shop & upgrade shop purchases across varied iron/gold/diamond/emerald counts |
| `DRILL_PVP` | **30–45 min** | 1.8 PvP spacing, W-tap sprint resets, bridge knockback duels |
| `DRILL_BED_BREAK` | **20–30 min** | Breaking wool/wood/endstone defenses and finishing exposed beds |
| `FULL_MATCH` | **1.5–3 hrs** | Complete 1v1 / Solo BedWars matches across multiple maps |
| `DAGGER_TAKEOVER` | **1–3 hrs** *(after BC v1)* | Live human corrections whenever the early AI drifts or slips — kills compounding error fast |

---

## In-Game Controls & Commands (Minecraft 26.3)

### Default Keybindings (rebindable in Options → Controls → *5ifthNN AI & Recorder*)
- **`F9`** — Start / Stop dataset recording immediately
- **`F8`** — Open **5ifthNN Control Panel GUI** (select recording mode, server profile, map tag, 1.8 PvP toggle, ONNX model loader, human guardrails, and failsafe settings)
- **`F10`** — Toggle Phase 2 AI Inference / **Emergency Kill-Switch** (instantly releases all keys)
- **`Right Alt`** *(hold)* — **HG-DAgger Human Takeover** (overrides AI seamlessly while recording your corrective actions!)

### Client Commands (`/fifthnn` or `/5nn`)
- `/5nn gui` — Open the Control Panel GUI
- `/5nn rec start [mode]` — Start recording (`FULL_MATCH`, `DRILL_BRIDGING`, `DRILL_SHOP`, `DRILL_PVP`, `DRILL_BED_BREAK`, `DAGGER_TAKEOVER`)
- `/5nn rec stop` — Flush & stop current recording safely
- `/5nn mode <mode>` — Switch recording mode
- `/5nn server <name>` / `/5nn map <name>` — Set multi-server / map conditioning tag
- `/5nn pvp <1.8|modern>` — Toggle 1.8 BedWars PvP recording/inference flag
- `/5nn model list` — List `.onnx` models in `.minecraft/config/5ifthnn/models/`
- `/5nn model load <filename.onnx>` — Hot-load an ONNX checkpoint
- `/5nn ai <on|off|dagger>` — Control Phase 2 AI engine
- `/5nn status` — Show recorder, failsafe, and ONNX runtime telemetry

---

## Building the Minecraft 26.3 Mod

Requires **Java 25** (Eclipse Temurin 25 or equivalent):

```bash
./gradlew build
```

The built mod JAR (with ONNX Runtime bundled) will be output to `build/libs/5ifthNN-0.1.0.jar`.

---

## Training & Exporting ONNX (Python / Google Colab L4)

See [colab/5ifthNN_Colab_L4.ipynb](colab/5ifthNN_Colab_L4.ipynb) or run locally:

```bash
cd python
pip install -r requirements.txt

# 1. Inspect a recording & verify 1:1 visual reconstruction
python3 -m fifthnn.dataset_io path/to/recording.5nn.gz
python3 -m fifthnn.visualizer path/to/recording.5nn.gz --frame 200 --out preview.png

# 2. Train Behavior Cloning bootstrap
python3 -m fifthnn.train_bc --data_dir ./recordings --out checkpoints/fifthnn_bc.pt --epochs 25

# 3. Improve via KL-Anchored PPO on real-server headless self-play rollouts
python3 -m fifthnn.train_ppo --rollout_dir ./rollouts --bc_anchor checkpoints/fifthnn_bc.pt --policy checkpoints/fifthnn_bc.pt --out checkpoints/fifthnn_ppo.pt

# 4. Export verified lightweight ONNX model for the Fabric mod
python3 -m fifthnn.export_onnx --ckpt checkpoints/fifthnn_ppo.pt --out models/fifthnn_v1.onnx
```

> **Safety Note:** Keep all automated AI evaluation, HG-DAgger, and PPO self-play strictly on your own **private local BedWars server**. Never run automated bots on public multiplayer servers.
