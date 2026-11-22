# 5ifthNN — Red-Teamed Local Training Helpers & Ergonomics Spec (`v0.4+` Incremental Plan)

> **Philosophy:** Build **slowly, one module at a time** so nothing breaks. Every kept helper has been **adversarially red-teamed** for BOTH **1-tick causal parity (`obs(t) → act(t+1)`)** AND **100% vanilla-physics / anti-cheat parity** (so your local server can keep real anti-cheat ON without poisoning `5ifthNN`).
>
> **No hardcoding** for any specific bot or player type — all combat/entity logic treats every non-teammate, non-shop-NPC `Player` generically.

---

## Step 0 — Single Centralized Gatekeeper (`LocalHelperManager.java`)
One single Java file owns the master permission state for all active gameplay helpers:

- **`LocalHelperManager.isPermittedServer(Minecraft mc, FifthNNConfig cfg)`**:
  - Returns `true` ONLY for singleplayer, localhost/LAN (`localhost`, `127.*`, `192.168.*`, `10.*`, `0.0.0.0`, `::1`), or explicit entries in `cfg.permittedHelperServers` (empty by default).
  - On any non-permitted server (or disconnect): sets `allHelpersAllowed = false`, disables every active module simultaneously, and releases all virtual keys.
- **Passive Recording Utilities Stay OUTSIDE `LocalHelperManager`** (never touch gameplay inputs, safe everywhere):
  - Surgical `F7` Scrap (`3s` single-tap / `10s` double-tap / `/5nn scrap episode`)
  - Auto-Requeue (random `2.5–4.5s` delay *after* `AUTO_STOP` closes `.5nn.gz`, aborts if ESC/chat/GUI opens)
  - Post-Match "BC Training Value" chat line

---

## Step 1 — Passive Recording & Local Drill Ergonomics (Zero Gameplay Inputs)
1. **Surgical `F7` Scrap:** `3s` (`60t`) single-tap / `10s` (`200t`) double-tap.
2. **Auto-Requeue:** Random `2.5–4.5s` post-`AUTO_STOP` delay, instant cancel key, pauses if chat/ESC opens (`0` recorded frames touched).
3. **Post-Match BC Training Value Summary:** Valid min, bridge %, PvP %, shop clicks, and recoveries.
4. **Local `15 TPS` Slow-Mo `0.75×` Sens Sync** *(gated by `LocalHelperManager`)*:
   - Scales mouse sensitivity by `0.75×` during `/tick rate 15` drills and restores `1.0×` on `20 TPS` / disconnect.
   - **Red-Team Fix:** When explicit slow-mo drill mode is ON, lower `BedWarsStateTracker`'s `lagSpike` TPS threshold (`< 10.0f` instead of `< 14.0f`) and normalize recorded `serverTpsEst` to `20.0` so `15 TPS` jitter never triggers false `LAG_SPIKE` quarantines or confuses `obs[s0+37]`!

---

## Step 2 — Discrete Hotbar & Pre-Swap Helpers (Tier S: Zero Causal Leak, Zero AC Risk)
Every action is a single vanilla `setSelectedSlot(slot)` using only signals already inside `FrameData`, with **no redundant `SetCarriedItem(sameSlot)` packets**:

1. **Smart Item-Type Hotkeys (`Sword`, `Blocks`, `Gapple`, `Pearl`, `Fireball`, `KB Stick`):**
   - `Blocks`: priority **`Wool → Terracotta → Wood → Endstone → Glass → Obsidian`**; **no-op if already holding a valid block** (clutch-safe + no redundant slot packet).
   - `Sword`: `Diamond > Iron > Stone > Wood` (KB Stick on its own key).
   - Disabled whenever `mc.gui.screen() != null` + 1-tick debounce.
2. **Anticipatory "Prep Break" Key:**
   - Strictly reads vanilla `mc.hitResult` (`<= 4.5b` — exact match to `FrameData.hitBlockCat`).
   - `BLOCK_WOOL` → `Shears` (only if owned), `BLOCK_WOOD_PLANKS` → best Axe, `BLOCK_ENDSTONE / TERRACOTTA / OBSIDIAN / SOLID_MAP` → best Pickaxe.
   - `BLOCK_BED_ENEMY` → keep current slot; `BLOCK_BED_OWN` → ignored; ignored if enemy `<= 3.2b` targeted or already holding target tool.
3. **Falling Clutch-Item Quick-Swap (`QuickClutchKey`):**
   - On manual keypress while `!onGround && fallDist > 1.5`: selects `Water Bucket` → `Ladder` (if aiming at vertical face) → `Wool` fallback; no-op if already holding it.
4. **Empty-Block-Stack Auto-Next-Stack Swap (`BlockRefillSwap`):**
   - **Red-Team Guard #1:** ONLY arms after `EVT_BLOCK_PLACED` empties the held block slot (`count: 1 → 0`), strictly ignoring manual `Q` item drops!
   - **Red-Team Guard #2 (No 0-Tick Leak):** Fires on **`t + 1` or `t + 2` (`50–100ms` delay)** after placement empties the slot, never on tick `t` itself, so `obs(t)` clearly shows empty hand before `act(t+1)` swaps!
5. **Post-Consume Return-to-Sword (`PostConsumeSwap`):**
   - **Red-Team Guard #1:** ONLY fires on **completed** consumption (`wasUsingConsumable && !isUsingItem()` AND consumable stack count decremented — strictly ignores canceled eating!).
   - **Red-Team Guard #2 (No 0-Tick Leak):** Fires **`1–2` ticks (`50–100ms`)** after completion (aborts if player manually swapped slots or opened GUI in that window).

---

## Step 3 — Personalized Click-Cadence Hold-to-Click (`LMB` & `RMB`)
1. **`ClickProfile` Sampler:**
   - Bootstraps empirical inter-click intervals from your own real clicking (shifted log-normal + `0.35` lag-1 autocorrelation, clamped `65–180ms`, rare `<8%` micro-bursts, natural pause gaps).
2. **LMB 5-State Context Machine (when LMB physically held & GUI closed):**
   - `DISABLED_UI`: screen/chat/pause/dead/spectator → pass-through.
   - `USE_ITEM_LOCKOUT`: `player.isUsingItem()` → paused completely.
   - `HOLD_MINE`: `BlockHitResult` & no enemy within `3.5b` cone → continuous vanilla hold (`left_state = 2`) with `3`-tick mining hysteresis.
   - `COMBAT_TAP`: `EntityHitResult` (non-teammate, non-shop-NPC) OR (`enemy <= 3.5b` inside `±30°` cone **AND `player.hasLineOfSight(enemy)`** — never clicks through walls!).
     - **Red-Team Fix (No 0-Tick Leak):** Require a **`1–2` tick (`50–100ms`) transition delay** when entering `COMBAT_TAP` from `IDLE_AIR` or `HOLD_MINE` so `obs(t)` always shows the enemy before `act(t+1)` clicks! Includes `200ms` post-kill cutoff.
   - `IDLE_AIR`: no enemy within `3.5b` LOS → initial click only, then idle.
3. **RMB 4-State Context Machine (when RMB physically held & GUI closed):**
   - Non-placeable item held → 100% vanilla pass-through.
   - Interactable (`Chest / Ender Chest / Bed`) while `!sneak` → paused.
   - Placeable block held + `BlockHitResult`:
     - **Red-Team Fix (No 0-Tick Leak):** When transitioning from `AIR_WAIT → FAST_PLACE`, require `BlockHitResult` to have been present for **`>= 1` tick** (aside from the player's own initial mouse press) so `obs(t-1)` always shows the target face before `act(t)` places!

---

## Step 4 — Context-Gated 1.8 Combat & Utility Weaves
1. **Context-Gated One-Tap W-Tap (`keyWTap` — strictly manual press):**
   - Requires: physical `W` held + `!sneak` + enemy within `5.0b` (`±45°`) + (`lastAttackTick <= 5` OR enemy `<= 3.8b`) + NOT mid-air over void + `8`-tick cooldown.
   - **Red-Team Fix:** ONLY **suppresses** `keyUp` for `2–3` ticks, then restores **physical keyboard state** (never forces `W=true` if your finger already left `W`!).
2. **Context-Gated One-Tap Blockhit (`keyBlockhit` — strictly manual press):**
   - Requires: holding Sword + GUI closed + `!isUsingItem()` + enemy `<= 4.2b` (`±45°`) + `lastAttackTick <= 4` + `8`-tick (`400ms`) cooldown.
   - Emits randomized **`1–3` tick (`50–150ms`)** vanilla `keyUse` pulse.
3. **Quick Utility Throw-and-Return (`QuickFireball` / `QuickPearl` / `QuickEgg`):**
   - **Strict Multi-Tick Separation (Never Swap+Click Same Tick!):**
     - Tick `t`: swap to utility slot
     - Tick `t + 1`: emit 1 vanilla `keyUse` click
     - Tick `t + 2..4` (randomized): return to previous Sword/Blocks slot
   - **Red-Team Guard:** Aborts return immediately if `mc.gui.screen() != null` (e.g. accidentally clicked a Shop NPC) or if player manually switched slots!

---

## Step 5 — Optional / Experimental Helpers (With Explicit Caveats)
1. **Human-Paced Shop Quick-Buy Presets (`Optional`):**
   - Active ONLY in `GUI_ITEM_SHOP`. Dynamically locates items by `classifyItem()`, verifies `econ` before each click, spaces clicks **`3–6` ticks (`150–300ms`)**, requires **zero movement keys held** (`!W && !A && !S && !D` — prevents `InvMove` flags & invalid shop-walking data!), and aborts if currency/inventory doesn't update within `6` ticks.
   - *Anti-Cheat Caveat:* Uses `handleContainerInput` without moving the mouse cursor across slots — clean on standard local Paper/BedWars1058 servers, can flag strict cursor-tracking InvClick checks.
2. **Post-Place Speed-Bridge Unsneak Assist (`Optional / Default OFF`):**
   - **Red-Team Fix:** Triggers on **`t + 1` or `t + 2`** after `EVT_BLOCK_PLACED` (never fixed `t+1` every single block!), stays **strictly OFF while `keyJump` is held**, clamps unsneak window to **`1–2` ticks (`50–100ms` — NEVER `4` ticks**, which walks `0.9–1.1` blocks backward right off a 1-block wool!), and immediately cancels unsneak if the block under feet is `AIR`.

---

## Cut / Permanently Excluded (Failed Red-Team Audit!)
- **CUT: Auto Jump-Reset:** Reacting on `hurtTime == 10` leaks 1 tick into `obs(t-1) → act(t)`, delaying 1 tick loses `onGround`, and auto-jumping every hit gets you juggled in 1.8 combos.
- **CUT: Panic-Only Cliff Catcher (`overhang >= 0.28`):** Triggering at `0.28` on tick `t` is both a **0-tick causal leak** (`obs(t-1)` didn't show `>= 0.28`) AND a classic **SafeWalk/Eagle anti-cheat signature**, while waiting until `t+1` falls into the void anyway—and it starves deep block-clutch data. Stick to manual sneak + `F7` 3s scrap.
- **NEVER ADD:** Aim Assist / Triggerbot / Auto-Clutch, Always-On SafeWalk / Auto-Bridge, Reactive AutoTool, ESP / Tracers / Hidden-Info Overlays, Packet Edits / Velocity / Reach.
