# 5ifthNN Technical Architecture & Schema Specification (v2)

## 1. Why Structured 1:1 Representation Beats Video Pixels

A raw 1080p video recording is multi-GB per hour and forces a 30M–100M parameter CNN to spend millions of samples re-learning basic depth, item icons, and health bars. Instead, **5ifthNN Phase 1 (v0.2 / Schema v2)** records a **lossless 1:1 structured scene snapshot** at 20 Hz directly from the Minecraft 26.3 client state:

- **Per-Frame `episodeId` + Quality/Teleport Bitmask (Schema v2)**: Every frame records `episodeId` (`int32`) right after `timestampMs` (`>qqiBBhf`), plus `QFLAG_TELEPORT_TICK` whenever a server warp/respawn moves the player >6 blocks in 1 tick (automatically zeroing fake teleport camera snaps). Both `train_bc.py` and `train_ppo.py` slice via `split_contiguous_segments()` so GRU hidden states and GAE returns never bleed across matches, lobbies, or teleports.
- **`13 × 7 × 13` (1,183 blocks) 3D Local Voxel Cube** centered on player feet (`±6` XZ, `-3..+3` Y), with every block tagged by semantic category + `isBreakable` + `isPlayerPlaced` (tracked universally via `knownAirBlocks` air→solid transitions so BOTH self and enemy bridges/defenses are separated from unbreakable map geometry).
- **32-Ray Panoramic Lidar** (16 horizontal rays @ 32b + 8 downward ledge/void rays @ 16b + 8 symmetric forward-fan rays @ 32b) for long-range island, bridge, and void perception.
- **Exact Crosshair Raycast (`HitResult`)** with face normal, distance, breakable/bed flags, real `MultiPlayerGameMode.destroyProgress` mining progress, and target entity ID.
- **Up to 16 Visible Players & 16 Threats/Dropped Items** (fireballs, TNT with fuse, arrows, pearls, golems, dropped iron/gold/diamonds/emeralds) with finite-difference multiplayer velocities (`EntityVelocityTracker`, fixing vanilla MC's zero-vel remote entities), dyed leather armor RGB team detection, fake-player shopkeeper NPC filtering, pose, weapon, armor, and hurt state.
- **Full-Map Persistent `ArenaLandmarkCache`**: Lazy `±44` block ring scanner discovers all beds across loaded chunks and detects broken beds; `ArmorStand` hologram scanner auto-discovers Diamond/Emerald generators and Item/Upgrade shopkeepers.
- **Full 41-Slot Inventory + Gear Tiers + Economy** (`iron, gold, diamond, emerald, blocks, gapples, fireballs, TNT, pearls, potions`).
- **Full 54-Slot Open Container/Shop GUI Snapshot + Exact Click Events** captured at `MultiPlayerGameMode.handleContainerInput` dispatch time.
- **Directional Sound Radar** (footsteps, invis footsteps, bed break, TNT fuse, fireball, pearl, potion).
- **Sub-Tick 1.8 PvP Input Fidelity** (WASD/Jump/Sneak/Sprint, exact `rawMouseDx/Dy`, exact `deltaYaw/deltaPitch`, exact `leftClicksThisTick` & `rightClicksThisTick` counts for high-CPS 1.8 clicking, hotbar presses, and GUI slot clicks).

All of this compresses via chunk-flushed GZIP (`syncFlush` every 40 ticks / 2 seconds) to **~12–18 MB/hour**, with **zero render-thread disk I/O** and crash-safe recovery. Python's `dataset_io.py` remains 100% backward-compatible with Schema v1 files.

---

## 2. Exact Parameter Budget (1,787,330 Parameters)

| Module | Shape / Spec | Parameters |
| :--- | :--- | ---: |
| `SpatialVoxelEncoder` | `Emb(32,6)` + 2 flags → `Conv2d(40→48→64→64)` → `Linear(576→128)` | ~148k |
| `ScalarEncoder` | `LN(456)` → `Linear(456→256)` → `Linear(256→192)` | ~167k |
| `SlowGRUCell` (2 Hz) | `GRUCell(320 → 192)` | ~296k |
| `FastGRUCell` (20 Hz) | `GRUCell(512 → 384)` | ~1,034k |
| `HeadTrunk + Factored Heads` | `Linear(384→256)` → Move(7), Yaw(25), Pitch(25), Click(3+3), Hotbar(10), GUI(56), Value(1) | ~132k |
| **Total Inference Model** | **ONNX FP32 ≈ 6.83 MB (Dynamic INT8 ≈ 2.0 MB)** | **1,787,330** |

---

## 3. Built-In Recorder & Execution Failsafes

1. **Non-Blocking Async Writer + Bounded Queue**: The client tick thread only serializes into a pre-allocated byte array and enqueues it; background I/O thread writes & GZIP-flushes every 2 seconds (40 ticks) so gameplay never stutters.
2. **Crash & Disconnect Resilience**: Every frame starts with magic `FRM1` + `uint32 payloadLen`. Client disconnect, world change, or JVM shutdown cleanly flushes & closes the file. Even on a hard crash/power cut, all frames prior to the last 2-second flush remain 100% readable.
3. **Automatic Noise & Teleport Tagging**: Frames during `PAUSE` menu, `CHAT` typing, unfocused window, `LOBBY`/hub/waiting cage, `SPECTATING`, `DEAD/RESPAWNING`, or server teleports (`>6b/tick`) are automatically flagged (`QFLAG_VALID_IN_GAME = 0`) so training cleanly filters and segments them without corrupting trajectories.
4. **Disk Space & Size Guards**: Refuses to start (and auto-stops cleanly) if free disk space drops below **1 GB**, and auto-rotates/stops if a single session exceeds **512 MB**.
5. **Phase 2 AI Human I/O Realism & Safety Interlocks**:
   - Sub-frame mouse GCD rotation (`MouseHandlerMixin.turnPlayer` → `HumanActionExecutor.onRenderFrameTurn`) matches physical mouse hardware sensitivity steps instead of choppy 20 Hz snaps.
   - Organic CPS micro-jitter + anti-`SNK+SPR` overlap guard + masked discrete GUI actions.
   - `localServerSafetyLock = true` restricts automated inference to localhost/LAN servers by default.
   - NaN/Inf output detector immediately disengages AI and releases all keys.
   - Latency watchdog logs warnings if ONNX exceeds 15 ms.
   - Pressing `F10`, `ESC`, disconnecting, or holding the **HG-DAgger Takeover Key (`Right Alt`)** immediately yields 100% control back to the human with zero stuck keys.
