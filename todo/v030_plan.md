# 5ifthNN `v0.3.0` — Exact File-by-File Implementation Plan

> **Target:** Ship `5ifthNN v0.3.0` with a **~150 KB standalone Fabric mod JAR** (separate one-time ONNX companion JAR), **foolproof zero-config auto-recording + 40-tick lagback/flicker ring buffer**, **`.tags.jsonl` sidecar (`F6`/`F7`)**, **1-tick causal parity (`obs(t) → act(t+1)`)**, **3-way Tactical Void vs Accidental Slip masking**, **ToggleSprint normalization**, **GUI-safe hotbar permutation**, **GRU burn-in**, and **4 training-only 0ms auxiliary heads**.
>
> **Hard Rules:**
> - **DO NOT** download Gradle locally — push to GitHub Actions CI for all Gradle/Java 25 builds, MC 26.3 `javap` Mixin verification, and PyTorch/ONNX tests.
> - **DO NOT** modify anything inside `/home/fivesaw/Panadol/` or PrismLauncher's `pathadol/` folder.
> - **0% hardcoded BedWars/PvP/bridging strategy** in Java.
> - Preserve Schema v1 & v2 backward compatibility in `python/fifthnn/dataset_io.py`.

---

## Phase A — Packaging Split (~150 KB Mod JAR + One-Time ONNX Companion JAR) & GPL-3.0

### 1. `gradle.properties`
- Bump `version=0.3.0`.

### 2. `src/main/resources/fabric.mod.json`
- Change `"license": "MIT"` → `"license": "GPL-3.0"`.
- Keep `"depends"` minimal (`fabricloader >=0.20.1`, `minecraft ~26.3`, `java >=25`, `fabric-api *`).
- Add `"suggests": { "fifthnn-onnxruntime": "*", "viafabricplus": "*" }`.

### 3. `build.gradle`
- **Remove** `include "com.microsoft.onnxruntime:onnxruntime:${project.onnxruntime_version}"` from the main JAR so `build/libs/5ifthNN-0.3.0.jar` shrinks from **55 MB → ~150 KB**!
- Keep `implementation "com.microsoft.onnxruntime:onnxruntime:${project.onnxruntime_version}"` so Phase 2 compiles cleanly against ONNX Runtime.
- Add a Gradle task `buildOnnxRuntimeAddon` (type `Jar`, `archiveBaseName = '5ifthNN-OnnxRuntime'`, `archiveVersion = project.onnxruntime_version`) that generates a standalone Fabric library mod JAR containing:
  - A minimal `fabric.mod.json` (`"id": "fifthnn-onnxruntime"`, `"name": "5ifthNN ONNX Runtime Lib"`, `"version": "1.32.0"`, `"license": "MIT"`, `"jars": [{"file": "META-INF/jars/onnxruntime-1.32.0.jar"}]`)
  - The resolved `onnxruntime-1.32.0.jar` placed under `META-INF/jars/onnxruntime-1.32.0.jar`.
- Hook `assemble.dependsOn(buildOnnxRuntimeAddon)` so `./gradlew build` emits BOTH:
  - `build/libs/5ifthNN-0.3.0.jar` (~150 KB)
  - `build/libs/5ifthNN-OnnxRuntime-1.32.0.jar` (~53 MB, install once in `mods/` only for Phase 2!).

### 4. `src/main/java/io/fifthnn/inference/OnnxPolicyEngine.java` & `AiController.java`
- Guard `OnnxPolicyEngine` initialization with a safe class-presence check (`Class.forName("ai.onnxruntime.OrtEnvironment")`) so when `5ifthNN-0.3.0.jar` runs standalone without `5ifthNN-OnnxRuntime`, Phase 1 recording works 100% cleanly and attempting `/5nn ai on` or loading an `.onnx` model prints a clear chat message: `"§e[5ifthNN] Install 5ifthNN-OnnxRuntime-1.32.0.jar in mods/ to enable Phase 2 AI."` (zero `NoClassDefFoundError`!).

---

## Phase B — Java Mod `v0.3.0`: Foolproof Auto-Recorder, Packet Lagback Quarantine & Sidecar Tags

### 5. `src/main/java/io/fifthnn/schema/FifthNNSchema.java`
- Keep `SCHEMA_VERSION = 2` (100% binary compatible with existing v0.2 `.5nn.gz` recordings! All extra tags/quarantines use existing `qualityFlags` + sidecar `.tags.jsonl`, or check bit availability in `qualityFlags`):
  - Existing bits in `qualityFlags` (uint8, bits `0..7` are currently full!):
    - `0`: `QFLAG_VALID_IN_GAME`
    - `1`: `QFLAG_WINDOW_FOCUSED`
    - `2`: `QFLAG_PAUSE_OR_CHAT`
    - `3`: `QFLAG_LOBBY_OR_SPEC`
    - `4`: `QFLAG_DEAD_OR_RESPAWN`
    - `5`: `QFLAG_AI_ACTIVE`
    - `6`: `QFLAG_DAGGER_TAKEOVER`
    - `7`: `QFLAG_TELEPORT_TICK`
  - **Cleanest zero-schema-break design:** Keep `SCHEMA_VERSION = 2` binary frame layout untouched, drop hard-quarantined lagback/flicker/freeze ticks inside the 40-tick pre-write ring buffer BEFORE disk write (preserving `tickIndex` gaps!), AND log rich structured event/tag ranges (`PEARL_LANDING`, `LAGBACK_QUARANTINE`, `FLICKER_QUARANTINE`, `USER_SCRAP_5S`, `USER_SCRAP_15S`, `USER_SCRAP_EPISODE`, `USER_HIGHLIGHT_8S`, `LAG_SPIKE`) into an append-only `<recording>.tags.jsonl` sidecar!
  - Add constants in `FifthNNSchema.java` for tag type strings so Java and Python stay 100% synchronized.

### 6. `src/main/java/io/fifthnn/mixin/ClientPacketListenerMixin.java` (**NEW**) + `fifthnn.mixins.json`
- Inject into `net.minecraft.client.multiplayer.ClientPacketListener`:
  - Target: `handleMovePlayer(Lnet/minecraft/network/protocol/game/ClientboundPlayerPositionPacket;)V` at `@At("HEAD")`.
  - **CRITICAL:** Add `javap -p -cp "$MC_JAR" net.minecraft.client.multiplayer.ClientPacketListener | grep -E "handleMovePlayer"` to `.github/workflows/build.yml` to verify exact MC 26.3 signature!
  - Notify `FifthNNClient.getStateTracker().onServerPositionPacket(mc)` on the client thread (note: `PacketUtils.ensureRunningOnSameThread` may run on Netty thread before rescheduling—use `mc.execute(...)` or check `mc.isSameThread()` so we only count it once on the render/client thread!).

### 7. `src/main/java/io/fifthnn/recorder/PlacementFlickerMonitor.java` (**NEW**)
- Tracks recently placed block coordinates `(BlockPos, long placedTick, int expectedCat)` for 15 ticks (bounded ring of max 64 entries).
- Each tick during `ACTIVE_MATCH`, inspects `level.getBlockState(pos)`:
  - If a placed block reverts to `AIR` within 15 ticks AND the player did NOT break it (`!recentlyBrokenBySelf`), fires a flicker event!
  - Tracks `sessionFlickerCount`, `flickersInLast60s` (rolling deque of timestamps), and returns whether a flicker just occurred so `DatasetRecorderManager` quarantines `[t - 5, t + 20]` and shows a one-time chat hint if `flickersInLast60s >= 5` (`"§e[5ifthNN] High block-flicker rate detected — make sure ViaFabricPlus is set to 1.8.x!"`).

### 8. `src/main/java/io/fifthnn/recorder/BedWarsStateTracker.java`
- Add **Multi-Signal Match Activation Vote (`isConfidentBedWarsMatch`)**:
  - Votes:
    1. Sidebar shows BedWars team lines (`sawBedwarsTeams`) OR received BedWars match-start system message.
    2. Player is alive, not spectator, not in hub/waiting cage, and `hasBedWarsGearOrBlocks(player)`.
    3. `ArenaLandmarkCache` has discovered `>= 1` bed OR `>= 1` BedWars shop/gen hologram.
  - Auto-recording transitions `STANDBY → ACTIVE_MATCH` when `votes >= 2` and `!isInHubOrWaitingLobby(player)`.
  - Auto-recording transitions `ACTIVE_MATCH → STANDBY` immediately on `MATCH_PHASE_POST_GAME`, `MATCH_PHASE_SPECTATING` (final death), hub/waiting-cage return, or disconnect (zero victory-dance frames!).
- Add **Pearl-vs-Lagback Detection**:
  - Track `lastPearlThrowTick` whenever player right-clicks while holding `ITEM_ENDER_PEARL` (or pearl count in `econ[8]` drops by 1).
  - Track `pendingServerPositionPacket` boolean set by `ClientPacketListenerMixin`.
  - When `pendingServerPositionPacket` fires during `ACTIVE_MATCH`:
    - Check if `(tick - lastPearlThrowTick <= 120)` AND (`jumpDist < 80.0`) AND (`player.isAlive()`) AND (`sawRecentPearlSound || player.hurtTime > 0 || jumpDist > 5.0`):
      - **YES → Pearl Landing:** emit `PEARL_LANDING` tag (do NOT quarantine, do NOT split sequence!).
      - **NO → Server Rubberband / Lagback:** emit `LAGBACK_QUARANTINE` event and increment `sessionLagbackCount`!
- Add **Wall-Clock Freeze & Ping-Spike Detection**:
  - Track rolling median ping (last 40 samples). Flag `lagSpikeThisTick` if `wallDt > 180ms`, `serverTpsEst < 14.0f`, or `pingMs > medianPing + 120`.

### 9. `src/main/java/io/fifthnn/recorder/DatasetRecorderManager.java`
- Implement **3-State Auto-Recorder** (`STANDBY`, `ACTIVE_MATCH`, `MANUAL_RECORDING`) governed by `config.autoRecordBedWars` (default **`true`**!).
  - When `autoRecordBedWars == true` and not manually recording: automatically starts a fresh per-match `.5nn.gz` on `STANDBY → ACTIVE_MATCH`, and automatically finishes + prunes on `ACTIVE_MATCH → STANDBY`!
  - Pressing `F9` forces manual start/stop anytime (for local practice drills with no scoreboard).
- Implement **40-Tick (2-Second) In-Memory Pre-Write Ring Buffer (`ArrayDeque<FrameData>`)**:
  - Every client tick increments `sessionTickIndex++` **unconditionally** while a session is active.
  - **CRITICAL INVARIANT #3:** If the current frame is non-gameplay (dead/respawning, pause, chat, Alt-Tab, lobby), do NOT enqueue it into the ring buffer, while `sessionTickIndex` STILL increments!
  - Enqueue valid gameplay frames into the 40-tick ring buffer. When `ringBuffer.size() > 40`, pop the oldest frame (`t - 40`) and send to `BinaryDatasetWriter.enqueueFrame(...)`.
  - On **Lagback (`[t - 30, t + 15]`)**, **Flicker (`[t - 5, t + 20]`)**, or **Freeze (`[t - 2, t + 10]`)**:
    - Remove/drop any buffered frames in the quarantine range straight from `ringBuffer` (leaving a real `tickIndex` gap!), set `postQuarantineTicksRemaining` for the forward tail (`+15` / `+20` / `+10`), and append a range record to `<recording>.tags.jsonl`!
  - On session stop / disconnect / JVM shutdown: flush remaining clean frames from `ringBuffer` into `BinaryDatasetWriter` and close cleanly.
- Implement **End-of-Match Auto-Prune & Rolling Storage Cap**:
  - After closing the `.5nn.gz` file, evaluate quality:
    - `validSeconds < 20.0` → auto-delete `.5nn.gz`, `.meta.json`, `.tags.jsonl` (silent).
    - `idlePct > 75.0` → auto-delete (silent).
    - `flickerRate > 0.30` (with `placements >= 10`) OR `lagbacksPerMin > 8.0` → auto-delete + 1-line chat notice explaining why.
  - Otherwise assign `qualityGrade` (`A`, `B`, `C`) in `.meta.json` and enforce `config.maxRecordingsFolderMb` (default `500` MB) by pruning oldest files if exceeded.
- Implement **Sidecar Tagging (`TagSidecarWriter`)**:
  - `onHighlightKey()` (`F6`): appends `{"startTick": cur-160, "endTick": cur, "tag": "USER_HIGHLIGHT_8S"}` + HUD toast `"★ Highlighted last 8s"`.
  - `onScrapKey()` (`F7`): single-tap appends `USER_SCRAP_5S` (`cur-100..cur`); second tap within `600ms` appends `USER_SCRAP_15S` (`cur-300..cur`) + HUD toast `"✂ Scrapped last 5s/15s"`.
  - `scrapCurrentEpisode()`: appends `USER_SCRAP_EPISODE` for current `episodeId`.
- Add `fov` and `mouseSensitivity` to `.5nn.gz` header JSON (diagnostic metadata only).
- Implement **`/5nn share` (`exportShareZip`)**: scans `recordings/` for Grade `A`/`B` `.5nn.gz` (+ matching `.meta.json` & `.tags.jsonl`) and writes `config/5ifthnn/5ifthNN_share_<timestamp>.zip`, printing file count, total minutes, and exact path in chat.

### 10. `src/main/java/io/fifthnn/mixin/BlockStatePredictionHandlerMixin.java` (**NEW**) + `fifthnn.mixins.json`
- **Built-in Anti-Cheat-Safe ViaVersion Flicker Guard (~30 lines, GPL-3.0):**
  - Auto-bypasses completely if `FabricLoader.getInstance().isModLoaded("viafabricplus")` is true (zero conflict with ViaFabricPlus).
  - Hooks `BlockStatePredictionHandler.endPredictionsUpTo(int, ClientLevel)` (verify exact MC 26.3 signature with `javap -p` in `.github/workflows/build.yml`!).
  - When a `ClientboundBlockChangedAckPacket` tries to revert a freshly placed solid block back to `AIR`, defers the revert for `3` ticks (`150ms`); if a real server `BlockUpdate` arrives with a non-air block before then, cancels the revert (eliminating the `.WWWWW.W` fall-through bug without touching hitboxes or movement packets!).
  - Configurable toggle `enableViaFlickerGuard = true` in `FifthNNConfig`.

### 11. `src/main/java/io/fifthnn/ui/FifthNNDependencyScreen.java` (**NEW**) + TitleScreen Corner Button
- **Smart, Zero-Auto-Download Recommended Dependency Prompt:**
  - Checks `FabricLoader.getInstance().isModLoaded("viafabricplus")` (and checks `fifthnn-onnxruntime` *only* if `config.getModelsDir()` contains at least one `.onnx` file).
  - If nothing recommended is missing → **100% silent** (no popup, no corner button).
  - If missing:
    - Shows `FifthNNDependencyScreen` once on `TitleScreen` initialization **ONLY if** `!config.suppressDependencyPrompt && (now - config.lastDependencyPromptMs > 14L * 86_400_000L)` (i.e. first launch, then at most once every 14 days — NEVER every launch!).
    - Clearly explains **why** `ViaFabricPlus` (`v5.1.3+` for MC 26.3, set server to `1.8.x`) is recommended for 1.8 BedWars servers (fixes `.WWWWW.W` block-ack flickers, water-clutch swim rubberbands, 1.8 sneaking height & thin-block hitboxes; notes `5ifthNN`'s built-in flicker guard is active as fallback).
    - Buttons per missing recommendation:
      - **Open Modrinth** (`https://modrinth.com/mod/viafabricplus` via MC's native link confirmation / `Util.getPlatform().openUri(...)`)
      - **Copy Name** (copies `"ViaFabricPlus"` to clipboard for users who prefer searching directly in Prism/Modrinth without clicking links)
      - **Continue** (sets `lastDependencyPromptMs = now`) and **Don't Show Again** (sets `suppressDependencyPrompt = true`).
    - Adds a tiny, unobtrusive corner button (`⚠ 5ifthNN Deps`) on `TitleScreen` via Fabric `ScreenEvents.AFTER_INIT` whenever a recommended dependency is missing so the user can reopen `FifthNNDependencyScreen` anytime.

### 12. `src/main/java/io/fifthnn/config/FifthNNConfig.java`, `FifthNNClient.java`, `FifthNNCommands.java`, `FifthNNHudOverlay.java`, `FifthNNControlScreen.java`
- `FifthNNConfig.java`:
  - Add `autoRecordBedWars = true`
  - Add `minimalHudMode = true` (clean corner status dot by default; full telemetry bar toggleable in `F8`)
  - Add `maxRecordingsFolderMb = 500`
  - Add `enableViaFlickerGuard = true`
  - Add `lastDependencyPromptMs = 0L` and `suppressDependencyPrompt = false`
- `FifthNNClient.java`:
  - Register `keyHighlight` (`F6`) and `keyScrap` (`F7`) alongside `F8`/`F9`/`F10`/`RALT`.
  - Register `ScreenEvents.AFTER_INIT` on `TitleScreen` for the smart dependency screen & corner button.
  - Call `recorder.onAutoTick(mc)` every `END_CLIENT_TICK` even when not manually recording so `STANDBY → ACTIVE_MATCH` works automatically.
- `FifthNNHudOverlay.java`:
  - Zero-leakage minimal mode: crisp corner dot (`●` green = clean recording, yellow = auto-quarantined/paused, red = flicker/ping warning; hidden in `STANDBY`) + transient 2s toast for `F6`/`F7`/lagback.
  - Full HUD mode: adds live `RB:<n> FLK:<n>` protocol-health badge + cross-session skill-diet summary read from `.meta.json` totals at startup/match-end.
- `FifthNNCommands.java` & `FifthNNControlScreen.java`:
  - Add `/5nn share`, `/5nn scrap episode`, `/5nn auto <on|off>`, `/5nn hud <minimal|full>`, `/5nn deps`.
  - Add `Auto-Record: ON/OFF`, `HUD: Minimal/Full`, and `Flicker Guard: ON/OFF` toggles in `F8` GUI.

---

## Phase C — Python ML Pipeline `v0.3.0`: Causal Parity, Smart Masking & 0ms Aux Heads

### 11. `python/fifthnn/dataset_io.py`
- Add `load_tags_sidecar(rec_path: Path) -> List[Dict]` reading optional `<rec_path>.tags.jsonl` (returns `[]` if absent, 100% backward-compatible with v0.1/v0.2 files).
- Keep `.5nn` / `.5nn.gz` filtering helper `find_recordings(target)` (never matches `.meta.json` or `.tags.jsonl`).

### 12. `python/fifthnn/audit.py`
- Update `split_contiguous_segments(frames, tags=None, max_tick_gap=2)`:
  - Respects `USER_SCRAP_5S`, `USER_SCRAP_15S`, `USER_SCRAP_EPISODE`, `LAGBACK_QUARANTINE`, `FLICKER_QUARANTINE`, `LAG_SPIKE` tags from `.tags.jsonl`.
  - Keeps `PEARL_LANDING` ticks continuous (does NOT split on `QFLAG_TELEPORT_TICK` when covered by a `PEARL_LANDING` tag or matching pearl-drop heuristic!).
  - **Enforces `tickIndex` gap splitting (`dt <= 0 or dt > max_tick_gap`)** so any frames dropped by the Java 40-tick ring buffer automatically reset GRU hidden states.
- Add **3-Way Void Classifier (`classify_void_falls(seg)`)** enforcing **CRITICAL INVARIANT #1**:
  - Detects void-fall termination ticks (`EVT_VOID_FALL` or `y` dropping rapidly below map floor into death).
  - Looks back 30 ticks (1.5s) and 40 ticks (2.0s):
    - **PvP Knockoff:** any `hurt_time > 0` in prior 40 ticks → `keep (1.0×)`.
    - **Accidental Slip (mask final 25 ticks = `0.0` weight):** `hurt_time == 0` in prior 40 ticks **AND** (`own_bed_alive == 0` **OR** `diamonds > 0 or emeralds > 0` **OR** actual bridging activity in prior 30 ticks: `KEY_SNEAK`, `right_clicks > 0`, or `EVT_BLOCK_PLACED`). **NEVER** triggers on `pitch > 50°` alone!
    - **Tactical Void (KEEP `1.0×–1.5×`):** `own_bed_alive == 1`, `hurt_time == 0`, NO bridging/sneaking/placing in prior 30 ticks, `diamonds == 0 and emeralds == 0`.
- Add **Edge-Recovery Detector (`detect_edge_recoveries(seg)`)**:
  - Finds moments where player takes knockback near void OR briefly overhangs/starts falling (`fall_dist > 0.3` / downward lidar void) and regains solid ground (`onGround == 1`) within 40 ticks without dying → tags for `5×–8×` oversampling!
- Add **Skill-Diet & Flicker/Rubberband Reporting** to `print_scorecard()`:
  - Reports Tactical Voids vs Accidental Slips vs Edge Recoveries, `W→AIR→W` flickers, midair upward rubberbands, and skill-bucket valid minutes (`Bridge`, `PvP`, `Shop`, `BedBreak`, `Macro`).

### 13. `python/fifthnn/features.py`
- **ToggleSprint Normalization in `encode_frame_targets()`:**
  - Normalize `move[6]` (sprint) to active sprint intent: `1.0 if ((sprinting_flag or key_sprint) and key_w and not key_sneak) else 0.0`.
- **GUI-Safe Hotbar Permutation (`hotbar_perm: Optional[List[int]]`) — CRITICAL INVARIANT #2:**
  - Add optional `hotbar_perm` (permutation of `0..8`) and `backpack_perm` (permutation of `9..35`) to `encode_frame_obs()` and `encode_frame_targets()`.
  - Apply `hotbar_perm` to `inventory[0:9]`, `selected_slot`, and `hotbar_target` **ONLY when `gui_type == 0` across the entire training window** (never permutes slots `0..8` during open shop/chest windows!).
- **Optional Sensor Jitter & Distant Dropout (`aug_noise: bool = False`):**
  - Training-only tiny Gaussian noise on remote player velocities (`σ=0.015`), lidar distances (`σ=0.004`), and `5%` dropout on distant (`> 20b`) landmarks/sounds.

### 14. `python/fifthnn/model.py`
- Add `dropout: float = 0.1` inside `ScalarEncoder` and `HeadTrunk` (active only in `.train()`, zero effect in `.eval()` / ONNX!).
- Upgrade training-only auxiliary head (`include_aux=True`, dropped when `include_aux=False` in `export_onnx.py` → **0 ms ONNX cost**):
  - `aux_void_1s`: `Linear(384 → 1)` (BCE: fall >4b or void within 20 ticks)
  - `aux_next_kin`: `Linear(384 → 4)` (MSE on egocentric next `(dx, dy, dz)` + BCE on next `onGround`)
  - `aux_enemy_fut`: `Linear(384 → 3)` (MSE on closest enemy relative `(fwd, right, dy)` at `t+10`, masked by enemy visibility)
  - `aux_macro_intent`: `Linear(192 → 4)` directly from **`h_slow`** (4-class CE: approaching Shop / Gen / Own Bed / Enemy Bed over next 40 ticks — directly trains the 2 Hz Slow GRU!).

### 15. `python/fifthnn/train_bc.py`
- **1-Tick Causal Parity Fix (`obs(t) → act(t+1)`):**
  - For each step `t` in `[start, start + seq_len)`, encode observations from `frames[t]` and action targets from **`frames[t + 1]`**!
  - Window requires `len(frames) >= seq_len + 22` (for `t+1` targets + `t+20` aux labels).
- **Per-Tick Loss Weighting & Masking (`tick_mask[B, T]`):**
  - **GRU Burn-In (`burn_in = 16` ticks):** `tick_mask[:, :16] = 0.0` (GRU hidden states warm up with zero loss on boundary ticks!).
  - **Accidental Slip Masking:** `tick_mask = 0.0` on final 25 ticks of detected accidental slips (Tactical Voids stay `1.0`!).
  - **AFK Shop Masking:** `gui_mask = 0.0` on open-shop streaks `> 80` ticks with zero clicks.
  - **Idle Downsampling:** `0.2×` weight on ticks inside prolonged zero-input streaks (`> 30` ticks).
  - **Event & Recovery Oversampling:**
    - `USER_HIGHLIGHT_8S` (`F6`): `2.5×`
    - Edge/knockback recoveries: `6.0×`
    - Bed breaks: `5.0×`
    - Shop/GUI clicks: `4.0×`
    - Active mining (`mining_progress > 0`): `3.0×`
    - HG-DAgger takeover: `3.0×`
    - Combat / Tactical Void recall: `2.0×` / `1.5×`
- **GUI-Safe Hotbar Augmentation:** Sample a random `0..8` hotbar permutation on closed-GUI windows (`all(f.gui_type == 0 for f in window)`).
- **Early Stopping + Rich Validation Telemetry:** Track episode-level Val Loss, `Val Move Acc`, `Val Aim ±1`, `Val Combat Acc`, `Val GUI Acc`, and stop cleanly if val loss stops improving.

### 16. `python/fifthnn/train_ppo.py`
- Apply the same **`obs(t) → act(t+1)`** causal alignment and `load_tags_sidecar()` support in `split_contiguous_segments()` so PPO matches BC and live inference tick-for-tick.

### 17. `python/tests/test_schema_and_io.py`, `python/tests/test_end_to_end_pipeline.py` & `.github/workflows/build.yml`
- Unit tests verifying:
  1. `obs(t) → act(t+1)` causal alignment in `BedWarsSequenceDataset`.
  2. Tactical Void (with `pitch = 75°` looking down, NO bridging/sneaking!) is **KEPT**, while bridging slip is **MASKED** (tests Invariant #1!).
  3. Hotbar permutation permutes `inventory[0:9]`, `selected_slot`, and `hotbar_target` consistently on closed-GUI windows and is **disabled** when `gui_type > 0` (tests Invariant #2!).
  4. Dropped ring-buffer frames leaving a `tickIndex > 2` gap split `split_contiguous_segments()` cleanly, while `PEARL_LANDING` tags stay continuous (tests Invariant #3!).
  5. ToggleSprint normalization (`SNK+SPR` → sprint target `0.0`).
  6. `FifthNNModel(include_aux=True)` trains with all 4 aux heads, and `export_onnx --random_init --quantize_int8` drops all aux heads (`include_aux=False`) with parity `< 1e-4` and `< 0.5 ms/tick` CPU!
- Update `.github/workflows/build.yml`:
  - Add `ClientPacketListener.handleMovePlayer` and `BlockStatePredictionHandler.endPredictionsUpTo` to `Verify Mixin Targets in MC 26.3 Bytecode`.
  - Verify `build/libs/5ifthNN-0.3.0.jar` is `< 1 MB` (~150 KB!) and `build/libs/5ifthNN-OnnxRuntime-1.32.0.jar` is emitted separately as its own artifact (`5ifthNN-onnxruntime-companion`).

---

## Execution Order for Hydrogen Medium
1. **Phase A** (Gradle split + GPL-3.0 + optional ONNX guard in `OnnxPolicyEngine`).
2. **Phase B** (`ClientPacketListenerMixin`, `BlockStatePredictionHandlerMixin` flicker guard, `PlacementFlickerMonitor`, `BedWarsStateTracker`, `DatasetRecorderManager` 3-state + 40-tick ring buffer + `.tags.jsonl` + `/5nn share`, `F6`/`F7` keys, `FifthNNDependencyScreen` + TitleScreen corner button, minimal HUD).
3. **Phase C** (`dataset_io.py`, `audit.py`, `features.py`, `model.py`, `train_bc.py`, `train_ppo.py`, tests).
4. Run local Python tests, push to `main`, watch GitHub Actions until 100% green (all MC 26.3 Mixin signatures verified + `< 1 MB` main JAR confirmed), then drop `5ifthNN-0.3.0.jar` (~150 KB!) into `5ifthNN-26.3/minecraft/mods/`.
