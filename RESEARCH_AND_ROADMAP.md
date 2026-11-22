# 5ifthNN — Research Findings, Forensic Log & Final `v0.3.0` Roadmap

> Master living document recording forensic server findings, causal ML discoveries, license/packaging architecture, and the locked `v0.3.0` specification.

---

## 1. Live Server Forensic Discoveries

### A. Pathadol Audit Baseline (Applied in `5ifthNN v0.2.0`)
- **Zero-velocity remote players in multiplayer:** Vanilla `Entity.getDeltaMovement()` on remote players is `[0, 0, 0]` on **79.6%** of ticks → fixed by `EntityVelocityTracker` finite-difference `(pos - prevPos) / dt` (**90.4%** non-zero enemy velocity on live PikaNetwork).
- **Dyed leather armor team detection:** Scoreboard teams on Pika/Jartex/Mineberry are often all `GRAY`, while dyed leather chestplate/helmet RGB accurately identifies every team → fixed via `DyedItemColor.getOrDefault`.
- **Full-map landmark memory:** `ArenaLandmarkCache` lazy `±44` ring bed scan + `ArmorStand` hologram scan achieved **99.9% Bed / 100.0% Gen / 100.0% Shop** coverage on live PikaNetwork Stonekeep.
- **Fake-player shopkeeper NPCs:** Excluded from enemy radar via `ArmorStand` hologram proximity.
- **User's real human baseline:** `|dYaw| p90` ≈ **4.65°–4.68°/tick**, `1-tick reversals > 15°` = **0**, `CPS mean` ≈ **9.5**, `reaction median` ≈ **218.5 ms**, `idle` ≈ **13.5%–26.3%**, compressed disk rate **~11.5 MB/hour** (`1.50 MB` for 7.8 min, `0` dropped frames, `0` NaNs).

### B. The Rooftop `.WWWWW.W` Block-Flicker & Rubberband Discovery (2026-11-22)
- Reconstructed directly from `20261122-173720_pika_Rooftop_FULL_MATCH.5nn.gz` (`ping ≈ 238–240ms`):
  - `Stonekeep #1`: `164` placements → `13` `W→AIR→W` flickers, `14` upward rubberbands
  - `Stonekeep #2`: `24` placements → `1` flicker, `1` rubberband
  - `Rooftop`: `42` placements → `23` `W→AIR→W` flickers (**55%**), `11` upward rubberbands (`+1.15` to `+1.59` blocks)
- **Exact root cause:** MC `1.19–26.3` `BlockStatePredictionHandler` receives PikaNetwork ViaVersion's synthesized `ClientboundBlockChangedAckPacket` **1 tick before** the 1.8.8 server's `BlockUpdate(WOOL)` arrives (`t+5` ≈ 240ms RTT), reverting wool to `AIR` for 1 tick (50ms). Player feet drop `54.00 → 53.92`, wool reappears at `t+6`, player clips through solid wool to `52.41`, and server rubberbands `+1.59b` at `t+11`.
- **Fix installed:** **`ViaFabricPlus 5.1.3`** (MC 26.3 Fabric) installed in `5ifthNN 26.3` Prism instance (`ViaFabricPlus-5.1.3.jar`). Selecting `1.8.x` for Pika/Jartex speaks native 1.8.9 protocol directly, bypassing ViaVersion's `BlockChangedAck` race and restoring 1.8.9 sneaking eye-height (`1.54` vs `1.27`).
- **License alignment:** Project license updated to **GPL-3.0**, which is fully compatible with ViaFabricPlus (also GPL-3.0). Keeping `ViaFabricPlus-5.1.3.jar` as a separate companion mod in `mods/` avoids re-downloading 9.9 MB on every `5ifthNN` update.

### C. Verified Code Audit: Egocentric Voxels & 90° Rotations
- Verified directly in `python/fifthnn/features.py` (`extract_egocentric_voxels`, lines 71–108):
  - Raw `.5nn.gz` `13×7×13` voxels are world-axis aligned, **and `extract_egocentric_voxels()` explicitly rotates them by `fr.yaw` into the player-egocentric `9×5×9` `(dy, fwd, right)` tensor** (with ±2b margin).
  - Therefore **90° world-axis rotations are mathematically a no-op** on the NN input; real signal-multiplying augmentations are **2× Left-Right Mirroring**, **GUI-safe `0..8` Hotbar Permutation**, **non-hotbar `9..35` Slot Permutation**, **Sensor Jitter + Distant Dropout**, and optional conservative open-movement yaw perturbation.

---

## 2. Locked `v0.3.0` Architecture & Bug-Catch Invariants

### A. Packaging, License & Smart Dependency UX (`~150 KB` Updates Forever!)
- **`5ifthNN-0.3.0.jar` (~150 KB):** Full Phase 1 auto-recorder + Phase 2 AI code with ONNX Runtime as a compile-only / optional runtime library (`OnnxPolicyEngine` gracefully detects missing `ai.onnxruntime` classes and disables only Phase 2 inference while Phase 1 works 100% standalone!).
- **`5ifthNN-OnnxRuntime-1.32.0.jar` (~53 MB):** Built once as a standalone Fabric library wrapper (install once in `mods/` only when entering Phase 2, never redownloaded on `5ifthNN` updates!).
- **Built-in Anti-Cheat-Safe Flicker Guard (GPL-3.0):** ~30-line `BlockStatePredictionHandler` Mixin holds premature ViaVersion `BlockChangedAck` `AIR` reversions for 2–3 ticks (auto-bypassed if `viafabricplus` is already loaded).
- **No-Auto-Download, No-Spam Recommended Dependency Screen:**
  - Checks `FabricLoader.getInstance().isModLoaded("viafabricplus")` (plus `fifthnn-onnxruntime` only if `.onnx` models exist in `models/`).
  - If all recommended deps are installed → completely silent.
  - If missing → shows a one-time screen on first launch (cooldown: 14 days, never every launch; plus "Don't remind me") explaining the exact 1.8 BedWars bugs (`BlockChangedAck` flickers, water-clutch swim pose, 1.8 sneak height/hitboxes), showing exact Modrinth name + recommended version (`ViaFabricPlus` `5.1.3+` for MC 26.3), with **Open Official Modrinth Page** AND **Copy Name** buttons (so users who don't trust links can search it manually).
  - While missing, shows a tiny unobtrusive corner button on `TitleScreen` to reopen the dependency menu anytime.
- Update `fabric.mod.json` `"license": "GPL-3.0"`.

### B. Phase 1 Foolproof Auto-Recorder ("Drop in `mods/` & Forget")
- **3-State Zero-Config Lifecycle:**
  1. **`STANDBY`** (0 bytes written): Silent in menus, hubs, waiting cages, SkyWars, Duels, survival.
  2. **`ACTIVE_MATCH`** (1 clean file per match): Auto-starts when **≥ 2 BedWars signals agree** (sidebar team beds, BedWars start message, bed/shop/gen `ArmorStand` in arena, dyed armor / wool / sword). Skips writing dead/respawn/pause/chat/Alt-Tab frames to disk.
  3. **`AUTO_STOP & AUTO_PRUNE`**: Immediately stops on Victory / Elimination / Hub warp / Disconnect (zero victory-dance spinning). Auto-deletes useless matches (`< 20s` valid, `> 75%` AFK, or unplayable `> 30%` block-flicker / `> 8` lagbacks/min storms) and enforces rolling folder cap (`500 MB` default ≈ 35–40 hrs).
- **40-Tick (2-Second) In-Memory Pre-Write Ring Buffer + Strict `tickIndex` Gap Invariant:**
  - Holds last 40 `FrameData` objects (~80 KB RAM) before handing `t - 40` to `BinaryDatasetWriter`.
  - **`ClientboundPlayerPositionPacket` (`ClientPacketListener.handleMovePlayer`) Mixin:** Catches **100% of server rubberbands of ANY size** (zero false positives on fireball/TNT jumps!).
    - Recognizes **Ender Pearl landings** (`lastPearlThrowTick` within 6s + `SND_PEARL` or `hurtTime > 0` + jump `< 80b` in same episode) as **`QFLAG_PEARL_LANDING`** (keeps sequence continuous!).
    - Non-pearl `PlayerPosition` packets quarantine `[t - 30, t + 15]` directly in the ring buffer before disk write!
  - **`W → AIR → W` Ghost-Block / Flicker Watcher:** Monitors placed block coordinates for 15 ticks; if a placed block vanishes without player mining, quarantines `[t - 5, t + 20]` and increments `FLK` counter (one-time chat hint if `> 5` flickers/min).
  - **Freeze / Ping-Spike Quarantine:** Quarantines ticks with `wallDt > 180ms`, `serverTpsEst < 14.0`, or ping spike `> +120ms` above rolling median.
  - **CRITICAL INVARIANT #3 (Ring-Buffer `tickIndex` Gaps):** Whenever the 40-tick ring buffer scrubs/drops lagback, flicker, or pause ticks before writing to GZIP, `sessionTickIndex` **MUST continue incrementing on every client tick** so written frames have a real `tick_index` jump (`dt > 2`) and `split_contiguous_segments()` resets the GRU hidden state cleanly across the scrubbed gap!
- **Append-Only `.tags.jsonl` Sidecar + Hotkeys (`F6`, `F7`, `F8`, `F9`):**
  - **Single-tap `F7`:** Writes `[tick - 100, tick, "USER_SCRAP_5S"]` (last 5s).
  - **Double-tap `F7` (within 600ms):** Upgrades to `[tick - 300, tick, "USER_SCRAP_15S"]` (last 15s).
  - **Single-tap `F6`:** Writes `[tick - 160, tick, "USER_HIGHLIGHT_8S"]` (`2.5×` BC weight for clean clutches/combos/bridges).
  - `/5nn scrap episode` tags current `episodeId` as scrapped; `/5nn share` zips Grade `A/B` recordings into a single shareable `.zip`.
- **Zero-Leakage Minimal HUD:**
  - Compact corner status dot (`●` green = clean, yellow = paused/quarantined, red = high flicker/ping warning; hidden in lobby) + toggleable full telemetry bar showing `RB:0 FLK:0` and cross-session skill-diet totals (`Bridge / PvP / Shop / BedBreak`).
  - Log `fov` and `mouseSensitivity` in `.5nn.gz` header metadata (diagnostics only).

### C. Python Training & Imitation-Learning Pipeline (`v0.3.0`)
1. **1-Tick Causal Parity Fix (`obs(t) → act(t+1)`):**
   - Pairs `obs(t)` with `act(t+1)` across `train_bc.py` and `train_ppo.py`, eliminating the `END_CLIENT_TICK` 1-tick causal leak (works retroactively on existing `.5nn.gz` recordings too!).
2. **ToggleSprint / Hold-Sprint Normalization:**
   - Normalizes sprint target (`move[6]`) to active sprint intent (`(sprinting || keySprint) && keyW && !sneaking`) so ToggleSprint and Hold-Sprint players combine cleanly.
3. **CRITICAL INVARIANT #1 — Tactical Void vs. Accidental Slip (NO Pitch-Only Trap!):**
   - **Never** classify `pitch > 50°` alone as an accidental slip (players glance down before intentionally voiding!).
   - **Accidental Slip (mask loss = `0` on final ~25 ticks before `EVT_VOID_FALL`):** ONLY when `hurtTime == 0` in prior 2s **AND** (`ownBedAlive == 0` **OR** unbanked `diamonds > 0 || emeralds > 0` **OR** actual bridging/placing/sneaking activity in the prior 1.5s: `KEY_SNEAK`, `right_clicks > 0`, or `EVT_BLOCK_PLACED`).
   - **Tactical Void (KEEP `1.0×–1.5×`):** `ownBedAlive == 1`, `hurtTime == 0`, NO bridging/sneaking/placing in prior 1.5s, `diamonds == 0 && emeralds == 0` (especially `distToHome > 25b` or enemy near own bed).
   - **PvP Knockoff (`hurtTime > 0` in prior 2s):** KEEP at `1.0×` — and **oversample successful edge/knockback recoveries `5×–8×`**!
4. **CRITICAL INVARIANT #2 — GUI-Safe Hotbar Permutation (`9!×` Invariance):**
   - Permuting hotbar slots `0..8` (`inventory[0:9]`, `selected_slot`, `hotbar_target`) MUST preserve exact GUI parity:
     - Either apply the permutation **only to windows with `gui_type == 0` throughout the entire window**, OR apply the exact same permutation to open-GUI hotbar click/swap targets (both `ContainerInput.SWAP` button `0..8` and container player-hotbar slots). Safest default: apply consistently across the entire window **only when `all(fr.gui_type == 0)`** (and also permute non-hotbar backpack slots `9..35` on closed-GUI windows).
5. **Idle, AFK-Shop & Active-Mining Weighting:**
   - Downsample prolonged idle runs (`> 30` consecutive zero-input ticks) by `0.2×`.
   - Mask idle shop staring (`> 80` ticks open shop GUI with zero clicks).
   - Oversample active block mining (`mining_progress > 0`) `3×` and `USER_HIGHLIGHT_8S` `2.5×`.
6. **GRU Burn-In (`16` ticks zero-loss warmup) + 4 Training-Only 0ms Auxiliary Heads:**
   - Drop auxiliary heads at ONNX export (`include_aux=False` → **0 ms inference cost**):
     1. `aux_void_1s` (BCE): Will player fall >4b / enter void within 20 ticks?
     2. `aux_next_kinematics` (MSE + BCE): Next-tick egocentric `(dx, dy, dz)` + `onGround`.
     3. `aux_enemy_future` (MSE, masked when enemy visible): Closest enemy relative `(fwd, right, dy)` at `t+10`.
     4. `aux_macro_intent` (4-class CE): Approaching Shop / Gen / Own Bed / Enemy Bed over next 40 ticks (supervises `Slow GRU @ 2Hz`).
   - Add `dropout=0.1` on encoder/trunk + early stopping on episode-level val loss.

---

## 3. High-Density Local Training Workflow (2–5 Hours = 20+ Hours Public Queue)
1. **Local High-Density Drills (~1.5 hrs, zero ViaVersion flicker):**
   - **Bridging + Deliberate Edge-Recovery (`25–30m`):** Straight/diagonal/staircase + intentional 10% near-edge slips & recoveries.
   - **Optional 15-TPS Slow-Mo Mechanics Drills:** Run local server at `15 TPS` (`/tick rate 15`) with **`0.75×` mouse sensitivity scaling** so per-tick `delta_yaw` stays 100% identical to 20 TPS while letting you execute surgical speed-bridges and clutches!
   - **Randomized-Economy Shop Speedrun (`15–20m`):** `600+` diverse shop purchases across varied iron/gold/dia/em counts.
   - **Defense-Breaching Drill (`15–20m`):** Layered wool/wood/endstone defenses over enemy beds.
   - **0ms Local Combat Sparring (`25–30m`):** Repurpose **Pathadol** as a local 0ms sparring dummy on narrow bridges & islands for 1.8 W-tap spacing, strafing, and knockback clutches!
2. **Real Multi-Map `FULL_MATCH` Games (`1.5–2.5 hrs` across 6–10 maps using `ViaFabricPlus 5.1.3` on `1.8.x`):**
   - Zero flickers, automatic `STANDBY → ACTIVE_MATCH → AUTO_PRUNE`, feeds macro flow to `Slow GRU @ 2Hz`.
3. **Early `BC v0.1` → Live HG-DAgger (`30–60m` on local server):**
   - Train `BC v0.1` at ~2 hours, load `.onnx`, hold `Right Alt` for 2–3s whenever the AI drifts or stalls (`3×` weighted).
4. **Phase 2B KL-Anchored PPO Self-Play:**
   - Full unbroken episodes on private local BedWars server (`kl_coef = 0.08`, one-time anti-loophole BedWars milestones).
