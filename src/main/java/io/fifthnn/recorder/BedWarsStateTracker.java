package io.fifthnn.recorder;

import io.fifthnn.schema.FifthNNSchema;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Tracks live BedWars match state, multi-game episode segmentation, auto server/map detection,
 * dyed leather team colors, teleport/lobby failsafe tagging, and anti-loophole milestone rewards
 * across PikaNetwork, Jartex, Mineberry, Hypixel, and local BedWars servers.
 */
public final class BedWarsStateTracker {
    private final Set<Long> playerPlacedBlocks = new HashSet<>(4096);
    private final ArenaLandmarkCache landmarkCache = new ArenaLandmarkCache();
    private final EntityVelocityTracker velocityTracker = new EntityVelocityTracker();

    private int episodeId = 1;
    private int matchPhase = FifthNNSchema.MATCH_PHASE_IN_GAME;
    private int prevMatchPhase = FifthNNSchema.MATCH_PHASE_UNKNOWN;
    private int ownTeamId = 0;
    private int ownBedAlive = 1;
    private int enemyBedsAliveMask = 0x7F;
    private int enemiesAliveCount = 7;
    private final byte[] teamUpgrades = new byte[5]; // sharp, prot, haste, forge, trap
    private int diamondTier = 1;
    private int emeraldTier = 1;
    private int matchElapsedTicks = 0;

    private String detectedMapName = "unknown";
    private String detectedServerProfile = "local";
    private float homeX, homeY, homeZ;
    private boolean hasHomeSpawn = false;

    private int pendingEventFlags = 0;
    private float pendingReward = 0.0f;

    private float prevHp = 20.0f;
    private boolean wasDead = false;
    private double prevSelfX, prevSelfY, prevSelfZ;
    private boolean hasPrevSelfPos = false;
    private long lastTickWallMs = 0L;
    private float serverTpsEst = 20.0f;
    private boolean lastFrameValidInGame = false;

    // v0.3.0: Auto-match detection, exact packet lagback vs Ender Pearl distinction, & lag-spike guard
    private boolean sawBedwarsScoreboard = false;
    private boolean sawBedwarsStartMessage = false;
    private volatile boolean pendingServerPositionPacket = false;
    private boolean lagbackThisTick = false;
    private boolean pearlLandingThisTick = false;
    private boolean lagSpikeThisTick = false;
    private boolean slowMoDrillMode = false;
    private int consecutiveSpectatorTicks = 0;
    private long trackerTickClock = 0L;
    private long lastPearlThrowTick = -9999L;
    private long lastPearlSoundTick = -9999L;
    private int prevPearlCount = 0;
    private final int[] recentPings = new int[40];
    private int recentPingCount = 0;
    private int recentPingCursor = 0;
    private final PlacementFlickerMonitor flickerMonitor = new PlacementFlickerMonitor();

    public synchronized void resetForNewMatch(boolean incrementEpisode) {
        if (incrementEpisode) {
            episodeId++;
        }
        playerPlacedBlocks.clear();
        landmarkCache.reset();
        velocityTracker.clear();
        ownTeamId = 0;
        ownBedAlive = 1;
        enemyBedsAliveMask = 0x7F;
        enemiesAliveCount = 7;
        for (int i = 0; i < 5; i++) teamUpgrades[i] = 0;
        diamondTier = 1;
        emeraldTier = 1;
        matchElapsedTicks = 0;
        hasHomeSpawn = false;
        hasPrevSelfPos = false;
        lastTickWallMs = 0L;
        consecutiveSpectatorTicks = 0;
        pendingEventFlags = 0;
        pendingReward = 0.0f;
        pendingServerPositionPacket = false;
        lagbackThisTick = false;
        pearlLandingThisTick = false;
        lagSpikeThisTick = false;
        lastPearlThrowTick = -9999L;
        lastPearlSoundTick = -9999L;
        prevPearlCount = 0;
        flickerMonitor.reset();
    }

    public synchronized void resetForNewMatch() {
        resetForNewMatch(true);
    }

    public synchronized void onServerPositionPacket() {
        pendingServerPositionPacket = true;
    }

    public synchronized void markBlockPlaced(BlockPos pos) {
        playerPlacedBlocks.add(pos.asLong());
        flickerMonitor.onBlockPlaced(pos, trackerTickClock);
        pendingEventFlags |= FifthNNSchema.EVT_BLOCK_PLACED;
    }

    public synchronized void markBlockBroken(BlockPos pos) {
        playerPlacedBlocks.remove(pos.asLong());
        flickerMonitor.onBlockBrokenBySelf(pos, trackerTickClock);
        pendingEventFlags |= FifthNNSchema.EVT_BLOCK_BROKEN;
    }

    public PlacementFlickerMonitor getFlickerMonitor() {
        return flickerMonitor;
    }

    public synchronized boolean isTrackedPlayerPlaced(BlockPos pos) {
        return playerPlacedBlocks.contains(pos.asLong()) || landmarkCache.isInferredPlayerPlaced(pos);
    }

    public synchronized void markItemBought() {
        pendingEventFlags |= FifthNNSchema.EVT_ITEM_BOUGHT;
    }

    public synchronized void markDamageDealt() {
        pendingEventFlags |= FifthNNSchema.EVT_DAMAGE_DEALT;
    }

    public ArenaLandmarkCache getLandmarkCache() {
        return landmarkCache;
    }

    public EntityVelocityTracker getVelocityTracker() {
        return velocityTracker;
    }

    /**
     * Parse system/game chat & actionbar messages across Pika, Jartex, Mineberry, Hypixel, and Paper plugins.
     */
    public synchronized void onChatMessage(String rawText, String selfName) {
        if (rawText == null || rawText.isEmpty()) return;
        String clean = stripFormatting(rawText);
        String lower = clean.toLowerCase(Locale.ROOT);
        // Strip server prefix such as "BedWars ▸ " on Mineberry/Jartex so "bedwars" never triggers bed-break rules
        String body = lower.contains("▸") ? lower.substring(lower.indexOf('▸') + 1).trim() : lower;
        String selfLower = selfName != null ? selfName.toLowerCase(Locale.ROOT) : "";

        // Hub / Waiting lobby cues
        if (body.contains("server selector") || body.contains("waiting for players")
                || body.contains("the game starts in") || body.contains("game starting in")) {
            matchPhase = FifthNNSchema.MATCH_PHASE_LOBBY;
            sawBedwarsStartMessage = false;
        }

        // Match start cues -> cleanly increment episodeId and wipe stale arena landmarks!
        if (body.contains("protect your bed") || body.contains("destroy the enemy beds")
                || body.contains("the game has started") || body.contains("all generators are now active")) {
            resetForNewMatch(true);
            matchPhase = FifthNNSchema.MATCH_PHASE_IN_GAME;
            sawBedwarsStartMessage = true;
        }

        // Map name from chat ("Map: Rooftop" or "Playing on Rooftop")
        if (body.startsWith("map:")) {
            String m = clean.substring(clean.toLowerCase(Locale.ROOT).indexOf("map:") + 4).trim();
            if (!m.isEmpty()) detectedMapName = m;
        }

        // Victory / Loss cues
        if (body.contains("victory!") || body.equals("victory") || body.contains("won the game")) {
            matchPhase = FifthNNSchema.MATCH_PHASE_POST_GAME;
            if (body.contains("victory") || (!selfLower.isEmpty() && body.contains(selfLower))) {
                pendingEventFlags |= FifthNNSchema.EVT_GAME_WON;
                pendingReward += 5.0f;
            }
        }
        if (body.contains("game over!") || body.contains("you have been eliminated")) {
            matchPhase = FifthNNSchema.MATCH_PHASE_SPECTATING;
            pendingEventFlags |= FifthNNSchema.EVT_GAME_LOST;
            pendingReward -= 5.0f;
        }

        // Bed destruction events (one-time irreversible milestones)
        if (body.contains("bed") && (body.contains("destroyed") || body.contains("broken"))) {
            if (body.contains("your bed")) {
                ownBedAlive = 0;
                pendingEventFlags |= FifthNNSchema.EVT_OWN_BED_LOST;
                pendingReward -= 1.5f;
            } else if (!selfLower.isEmpty() && body.contains("by " + selfLower)) {
                pendingEventFlags |= FifthNNSchema.EVT_BED_BROKEN_BY_ME;
                pendingReward += 2.0f;
            }
        }

        // Kill / Final kill events
        if (!selfLower.isEmpty() && !body.contains("bed")
                && (body.contains("by " + selfLower))) {
            if (body.contains("final kill")) {
                pendingEventFlags |= FifthNNSchema.EVT_FINAL_KILL;
                pendingReward += 1.5f;
            } else {
                pendingEventFlags |= FifthNNSchema.EVT_KILL;
                pendingReward += 0.05f;
            }
        }

        // Upgrades & generator tiers
        if (body.contains("sharpened swords") || body.contains("sharpness")) {
            if (body.contains("purchased") || body.contains("bought")) {
                teamUpgrades[0] = (byte) Math.min(4, teamUpgrades[0] + 1);
            }
        }
        if (body.contains("reinforced armor") || body.contains("protection")) {
            if (body.contains("purchased") || body.contains("bought")) {
                teamUpgrades[1] = (byte) Math.min(4, teamUpgrades[1] + 1);
            }
        }
        if (body.contains("maniac miner") || body.contains("haste")) {
            if (body.contains("purchased") || body.contains("bought")) {
                teamUpgrades[2] = (byte) Math.min(2, teamUpgrades[2] + 1);
            }
        }
        if (body.contains("iron forge") || body.contains("gold forge") || body.contains("molten forge")) {
            if (body.contains("purchased") || body.contains("bought")) {
                teamUpgrades[3] = (byte) Math.min(4, teamUpgrades[3] + 1);
            }
        }
        if (body.contains("trap") && (body.contains("purchased") || body.contains("bought"))) {
            teamUpgrades[4] = (byte) Math.min(3, teamUpgrades[4] + 1);
        }
        if (body.contains("diamond") && body.contains("tier ii")) diamondTier = 2;
        if (body.contains("diamond") && body.contains("tier iii")) diamondTier = 3;
        if (body.contains("emerald") && body.contains("tier ii")) emeraldTier = 2;
        if (body.contains("emerald") && body.contains("tier iii")) emeraldTier = 3;
    }

    /**
     * Pre-tick state detection (runs BEFORE voxel/entity capture so ownTeamId and isInGame are
     * already accurate for bed classification, landmark scanning, and player-placed tracking!).
     */
    public synchronized void preCaptureUpdate(Minecraft mc, LocalPlayer player) {
        detectedServerProfile = detectServerProfile(mc);
        parseScoreboard(mc, player);

        // Prefer dyed leather armor RGB (works on Pika/Jartex/Mineberry/Hypixel even when scoreboard team is GRAY!)
        int armorTeam = inferTeamIdFromArmor(player);
        if (armorTeam > 0) {
            ownTeamId = armorTeam;
        } else if (ownTeamId == 0 && player.getTeam() != null) {
            ownTeamId = teamColorToTeamId(player.getTeam().getColor());
        }

        // Multi-signal Hub / Waiting-Box detection (NEVER treat temporary 5s BedWars respawn spectator as Lobby!)
        boolean tempBedWarsRespawn = player.isSpectator()
                && matchPhase == FifthNNSchema.MATCH_PHASE_IN_GAME
                && sawBedwarsScoreboard
                && (ownBedAlive == 1 || consecutiveSpectatorTicks < 140);
        if (player.isSpectator() && matchPhase == FifthNNSchema.MATCH_PHASE_IN_GAME && sawBedwarsScoreboard) {
            consecutiveSpectatorTicks++;
            if (ownBedAlive == 0 && consecutiveSpectatorTicks >= 140) {
                matchPhase = FifthNNSchema.MATCH_PHASE_SPECTATING;
            }
        } else {
            consecutiveSpectatorTicks = 0;
        }

        boolean looksLikeHubOrWaiting = !tempBedWarsRespawn && isInHubOrWaitingLobby(player);
        if (looksLikeHubOrWaiting) {
            matchPhase = FifthNNSchema.MATCH_PHASE_LOBBY;
        } else if (matchPhase == FifthNNSchema.MATCH_PHASE_LOBBY && hasBedWarsGearOrBlocks(player)) {
            // Clean transition from waiting box / hub into live BedWars match!
            resetForNewMatch(true);
            matchPhase = FifthNNSchema.MATCH_PHASE_IN_GAME;
            ownTeamId = inferTeamIdFromArmor(player);
        }

        // Detect transition from POST_GAME / SPECTATING back to a brand-new live match
        if ((prevMatchPhase == FifthNNSchema.MATCH_PHASE_POST_GAME || prevMatchPhase == FifthNNSchema.MATCH_PHASE_SPECTATING)
                && matchPhase == FifthNNSchema.MATCH_PHASE_IN_GAME && !player.isSpectator() && player.isAlive()) {
            resetForNewMatch(true);
            ownTeamId = inferTeamIdFromArmor(player);
        }
        prevMatchPhase = matchPhase;
    }

    public synchronized void tickAndFillFrame(Minecraft mc, LocalPlayer player, FrameData fr, boolean autoTagLobby) {
        trackerTickClock++;
        lagbackThisTick = false;
        pearlLandingThisTick = false;
        lagSpikeThisTick = false;

        // Track Ender Pearl throws & pearl sound cues for pearl-vs-lagback distinction
        int curPearls = fr.econ[8];
        if (curPearls < prevPearlCount) {
            lastPearlThrowTick = trackerTickClock;
        }
        prevPearlCount = curPearls;
        if (fr.rightClicksThisTick > 0 && fr.selectedSlot >= 0 && fr.selectedSlot < 9
                && (fr.invItemCat[fr.selectedSlot] & 0xFF) == FifthNNSchema.ITEM_ENDER_PEARL) {
            lastPearlThrowTick = trackerTickClock;
        }
        for (var snd : fr.sounds) {
            if (snd.soundCat() == FifthNNSchema.SND_PEARL) {
                lastPearlSoundTick = trackerTickClock;
                break;
            }
        }

        // Estimate server TPS via wall-clock tick interval + detect client freezes / ping spikes
        long now = System.currentTimeMillis();
        long wallDt = 50L;
        if (lastTickWallMs > 0) {
            wallDt = now - lastTickWallMs;
            if (wallDt > 5 && wallDt < 2000) {
                float instTps = Math.min(20.0f, 1000.0f / wallDt);
                serverTpsEst = 0.9f * serverTpsEst + 0.1f * instTps;
            }
        }
        lastTickWallMs = now;
        fr.serverTpsEst = slowMoDrillMode ? 20.0f : serverTpsEst;

        int ping = fr.pingMs;
        int medianPing = computeMedianPing();
        if (ping > 0) {
            recentPings[recentPingCursor] = ping;
            recentPingCursor = (recentPingCursor + 1) % recentPings.length;
            if (recentPingCount < recentPings.length) recentPingCount++;
        }
        float minTpsFloor = slowMoDrillMode ? 10.0f : 14.0f;
        if (wallDt > 180L || serverTpsEst < minTpsFloor || (recentPingCount >= 10 && ping > medianPing + 120)) {
            lagSpikeThisTick = true;
        }

        // Detect exact server position sync packet (ClientboundPlayerPositionPacket) OR >6b jump
        boolean gotPosPacket = pendingServerPositionPacket;
        pendingServerPositionPacket = false;

        double jumpDist = 0.0;
        if (hasPrevSelfPos) {
            jumpDist = Math.sqrt(
                    sq(player.getX() - prevSelfX) + sq(player.getY() - prevSelfY) + sq(player.getZ() - prevSelfZ)
            );
        }

        boolean teleportedThisTick = false;
        if (hasPrevSelfPos && (gotPosPacket || jumpDist > 6.0)) {
            boolean recentPearl = (trackerTickClock - lastPearlThrowTick <= 120L);
            boolean pearlEvidence = (trackerTickClock - lastPearlSoundTick <= 15L) || player.hurtTime > 0 || jumpDist > 5.0;
            if (recentPearl && pearlEvidence && jumpDist < 80.0 && player.isAlive() && !player.isSpectator()) {
                // Legitimate Ender Pearl landing: keep sequence continuous!
                pearlLandingThisTick = true;
            } else {
                teleportedThisTick = true;
                lagbackThisTick = true;
                // Zero out fake rotation snap caused by server teleport/rubberband
                fr.deltaYaw = 0.0f;
                fr.deltaPitch = 0.0f;
            }
        }
        prevSelfX = player.getX();
        prevSelfY = player.getY();
        prevSelfZ = player.getZ();
        hasPrevSelfPos = true;

        if (!hasHomeSpawn && matchPhase == FifthNNSchema.MATCH_PHASE_IN_GAME && player.isAlive() && !player.isSpectator()) {
            homeX = (float) player.getX();
            homeY = (float) player.getY();
            homeZ = (float) player.getZ();
            hasHomeSpawn = true;
        }

        // Detect HP drop / death / void fall
        float curHp = player.getHealth();
        if (curHp < prevHp - 0.1f) {
            pendingEventFlags |= FifthNNSchema.EVT_DAMAGE_TAKEN;
        }
        prevHp = curHp;

        boolean deadNow = !player.isAlive() || curHp <= 0.0f;
        boolean tempRespawn = player.isSpectator()
                && matchPhase == FifthNNSchema.MATCH_PHASE_IN_GAME
                && sawBedwarsScoreboard
                && (ownBedAlive == 1 || consecutiveSpectatorTicks < 140);

        if (deadNow || tempRespawn || player.isSpectator()) {
            // Reset position baseline while dead/respawning so teleporting back to home spawn never triggers fake lagback!
            hasPrevSelfPos = false;
        }

        if (deadNow && !wasDead) {
            pendingEventFlags |= FifthNNSchema.EVT_DEATH;
            if (player.getY() < 15.0) {
                pendingEventFlags |= FifthNNSchema.EVT_VOID_FALL;
            }
        }
        wasDead = deadNow;

        if (tempRespawn || deadNow) {
            fr.matchPhase = FifthNNSchema.MATCH_PHASE_RESPAWNING;
        } else if (player.isSpectator()) {
            fr.matchPhase = FifthNNSchema.MATCH_PHASE_SPECTATING;
        } else {
            fr.matchPhase = matchPhase;
        }

        if (fr.matchPhase == FifthNNSchema.MATCH_PHASE_IN_GAME) {
            matchElapsedTicks++;
            pendingReward -= 0.0005f; // tiny per-tick time pressure against camping
        }

        // Copy state into FrameData
        fr.episodeId = episodeId;
        fr.ownTeamId = ownTeamId;
        fr.ownBedAlive = ownBedAlive;
        fr.enemyBedsAliveMask = enemyBedsAliveMask;
        fr.enemiesAliveCount = enemiesAliveCount;
        System.arraycopy(teamUpgrades, 0, fr.teamUpgrades, 0, 5);
        fr.diamondTier = diamondTier;
        fr.emeraldTier = emeraldTier;
        fr.matchElapsedTicks = matchElapsedTicks;
        fr.eventFlagsThisTick = pendingEventFlags;
        fr.rewardDelta = pendingReward;

        if (hasHomeSpawn && fr.landmarks.size() < FifthNNSchema.MAX_LANDMARKS_RAW) {
            fr.landmarks.add(new FrameData.LandmarkEntry(
                    FifthNNSchema.LM_HOME_SPAWN, ownTeamId, homeX, homeY, homeZ, ownBedAlive
            ));
        }

        // Compute quality flags (strict noise & teleport failsafe tagging!)
        int qFlags = 0;
        boolean focused = mc.isWindowActive();
        if (focused) qFlags |= FifthNNSchema.QFLAG_WINDOW_FOCUSED;
        // Filter pause, chat, and non-container mod/settings menus (e.g. F8 Control Panel) from gameplay data
        boolean pauseOrChat = (fr.guiType == FifthNNSchema.GUI_PAUSE
                || fr.guiType == FifthNNSchema.GUI_CHAT
                || fr.guiType == FifthNNSchema.GUI_OTHER);
        if (pauseOrChat) qFlags |= FifthNNSchema.QFLAG_PAUSE_OR_CHAT;
        if (deadNow || fr.matchPhase == FifthNNSchema.MATCH_PHASE_RESPAWNING) qFlags |= FifthNNSchema.QFLAG_DEAD_OR_RESPAWN;
        boolean lobbyOrSpec = (fr.matchPhase == FifthNNSchema.MATCH_PHASE_LOBBY
                || fr.matchPhase == FifthNNSchema.MATCH_PHASE_SPECTATING
                || fr.matchPhase == FifthNNSchema.MATCH_PHASE_POST_GAME);
        if (lobbyOrSpec) qFlags |= FifthNNSchema.QFLAG_LOBBY_OR_SPEC;
        if (teleportedThisTick) qFlags |= FifthNNSchema.QFLAG_TELEPORT_TICK;

        boolean valid = !pauseOrChat && !deadNow && fr.matchPhase != FifthNNSchema.MATCH_PHASE_RESPAWNING
                && focused && !teleportedThisTick && (!autoTagLobby || !lobbyOrSpec);
        if (valid) qFlags |= FifthNNSchema.QFLAG_VALID_IN_GAME;
        fr.qualityFlags = qFlags;
        lastFrameValidInGame = valid;

        // Clear per-tick accumulators
        pendingEventFlags = 0;
        pendingReward = 0.0f;
    }

    private static boolean isInHubOrWaitingLobby(LocalPlayer player) {
        if (player.isSpectator()) return true;

        boolean hasGearOrBlocks = hasBedWarsGearOrBlocks(player);
        // Check hotbar for unmistakable hub / waiting-lobby selector items
        int hubItems = 0;
        for (int i = 0; i < 9; i++) {
            ItemStack st = player.getInventory().getItem(i);
            if (st.isEmpty()) continue;
            String path = BuiltInRegistries.ITEM.getKey(st.getItem()).getPath();
            if (path.equals("nether_star") || path.equals("clock") || path.equals("barrier")
                    || path.equals("blaze_powder") || path.equals("paper") || path.endsWith("_bed")
                    || path.equals("book") || path.equals("written_book")) {
                hubItems++;
            } else if (path.equals("compass") && !hasGearOrBlocks) {
                hubItems++;
            }
        }
        if (hubItems > 0 && !hasGearOrBlocks) return true;
        // Hub world far-out coordinates (e.g. PikaNetwork main hub at x=-1720 with no BedWars gear/blocks)
        if (!hasGearOrBlocks && (Math.abs(player.getX()) > 450.0 || Math.abs(player.getZ()) > 450.0)) return true;
        // Waiting cage high above the BedWars arena (typically Y=115..160 with no sword/armor/blocks)
        return player.getY() > 112.0 && !hasGearOrBlocks;
    }

    private static boolean hasBedWarsGearOrBlocks(LocalPlayer player) {
        if (!player.getItemBySlot(EquipmentSlot.CHEST).isEmpty() || !player.getItemBySlot(EquipmentSlot.FEET).isEmpty()) {
            return true;
        }
        for (int i = 0; i < 9; i++) {
            ItemStack st = player.getInventory().getItem(i);
            if (st.isEmpty()) continue;
            String path = BuiltInRegistries.ITEM.getKey(st.getItem()).getPath();
            if (path.endsWith("_sword") || path.endsWith("_pickaxe") || path.endsWith("_axe") || path.equals("shears")
                    || path.endsWith("_wool") || path.endsWith("_planks") || path.equals("end_stone")
                    || path.contains("terracotta") || path.equals("obsidian") || path.equals("ladder")) {
                return true;
            }
        }
        return false;
    }

    private void parseScoreboard(Minecraft mc, LocalPlayer player) {
        if (mc.level == null) return;
        Scoreboard sb = mc.level.getScoreboard();
        Objective sidebar = sb.getDisplayObjective(DisplaySlot.SIDEBAR);
        if (sidebar == null) return;

        int livingEnemyBeds = 0;
        int livingTeams = 0;
        boolean sawBedwarsTeams = false;
        boolean sawLobbyLine = false;

        for (PlayerScoreEntry entry : sb.listPlayerScores(sidebar)) {
            PlayerTeam team = sb.getPlayersTeam(entry.owner());
            String line = stripFormatting(PlayerTeam.formatNameForTeam(team, entry.ownerName()).getString());
            String lower = line.toLowerCase(Locale.ROOT);

            if (lower.startsWith("map:")) {
                String m = line.substring(4).trim();
                if (!m.isEmpty()) detectedMapName = m;
            }
            if (lower.contains("waiting...") || lower.contains("starting in")
                    || lower.contains("players:") || lower.contains("online:")) {
                sawLobbyLine = true;
            }
            if (lower.contains("diamond ii")) diamondTier = Math.max(diamondTier, 2);
            if (lower.contains("diamond iii")) diamondTier = Math.max(diamondTier, 3);
            if (lower.contains("emerald ii")) emeraldTier = Math.max(emeraldTier, 2);
            if (lower.contains("emerald iii")) emeraldTier = Math.max(emeraldTier, 3);

            int tId = parseTeamColorLine(lower);
            if (tId > 0) {
                sawBedwarsTeams = true;
                boolean isYou = lower.contains("you");
                boolean hasBed = line.contains("✔") || line.contains("✓") || lower.contains("alive");
                boolean eliminated = line.contains("✘") || line.contains("✗") || lower.contains("dead");
                if (isYou) {
                    ownTeamId = tId;
                    if (hasBed) ownBedAlive = 1;
                    else if (eliminated) ownBedAlive = 0;
                } else {
                    if (hasBed) livingEnemyBeds |= (1 << ((tId - 1) & 7));
                    if (!eliminated) livingTeams++;
                }
            }
        }

        sawBedwarsScoreboard = sawBedwarsTeams;
        if (sawLobbyLine && !sawBedwarsTeams) {
            matchPhase = FifthNNSchema.MATCH_PHASE_LOBBY;
        } else if (sawBedwarsTeams) {
            // Never clobber POST_GAME (victory dance) or SPECTATING back into IN_GAME while sidebar still shows teams!
            if (matchPhase != FifthNNSchema.MATCH_PHASE_POST_GAME && matchPhase != FifthNNSchema.MATCH_PHASE_SPECTATING) {
                matchPhase = FifthNNSchema.MATCH_PHASE_IN_GAME;
            }
            enemyBedsAliveMask = livingEnemyBeds;
            enemiesAliveCount = livingTeams;
        }
    }

    /**
     * Multi-signal vote (>= 2 signals) confirming the player is in an active BedWars match
     * (never triggers in hubs, waiting cages, SkyWars, Duels, or survival).
     */
    public synchronized boolean isConfidentBedWarsMatch(Minecraft mc, LocalPlayer player) {
        if (mc == null || player == null || mc.level == null) return false;
        if (player.isSpectator() || !player.isAlive()) return false;
        if (matchPhase == FifthNNSchema.MATCH_PHASE_POST_GAME || matchPhase == FifthNNSchema.MATCH_PHASE_SPECTATING) {
            return false;
        }
        if (isInHubOrWaitingLobby(player)) return false;

        int votes = 0;
        if (sawBedwarsScoreboard || sawBedwarsStartMessage) votes++;
        if (hasBedWarsGearOrBlocks(player)) votes++;
        if (landmarkCache.getTrackedBedCount() > 0 || landmarkCache.getTrackedShopCount() > 0 || landmarkCache.getTrackedGenCount() > 0) {
            votes++;
        }
        return votes >= 2;
    }

    public synchronized boolean shouldAutoStopMatch(LocalPlayer player) {
        if (player == null) return true;
        if (matchPhase == FifthNNSchema.MATCH_PHASE_POST_GAME || matchPhase == FifthNNSchema.MATCH_PHASE_SPECTATING) {
            return true;
        }
        if (matchPhase == FifthNNSchema.MATCH_PHASE_LOBBY && isInHubOrWaitingLobby(player)) {
            return true;
        }
        return false;
    }

    private int computeMedianPing() {
        if (recentPingCount == 0) return 0;
        int[] copy = new int[recentPingCount];
        System.arraycopy(recentPings, 0, copy, 0, recentPingCount);
        java.util.Arrays.sort(copy);
        return copy[copy.length / 2];
    }

    public boolean isLagbackThisTick() {
        return lagbackThisTick;
    }

    public boolean isPearlLandingThisTick() {
        return pearlLandingThisTick;
    }

    public boolean isLagSpikeThisTick() {
        return lagSpikeThisTick;
    }

    public long getTrackerTickClock() {
        return trackerTickClock;
    }

    public void setSlowMoDrillMode(boolean enabled) {
        this.slowMoDrillMode = enabled;
    }

    /**
     * Detect BedWars team color from dyed leather armor RGB (works reliably across
     * PikaNetwork, Jartex, Mineberry, Hypixel, and local Paper plugins!).
     */
    public static int inferTeamIdFromArmor(Player p) {
        if (p == null) return 0;
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.CHEST, EquipmentSlot.HEAD}) {
            ItemStack st = p.getItemBySlot(slot);
            if (st.isEmpty()) continue;
            int rgb = DyedItemColor.getOrDefault(st, -1);
            int tid = inferTeamIdFromDyeRgb(rgb);
            if (tid > 0) return tid;
        }
        return 0;
    }

    public static int inferTeamIdFromDyeRgb(int rgb) {
        if (rgb == -1) return 0;
        int masked = rgb & 0x00FFFFFF;
        // Default undyed leather brown (0xA06540) or pure black
        if (masked == 0 || masked == 0x00A06540) return 0;
        int r = (masked >> 16) & 0xFF;
        int g = (masked >> 8) & 0xFF;
        int b = masked & 0xFF;

        int[][] palette = {
                {1, 255, 0, 0}, {1, 255, 85, 85}, {1, 176, 46, 38},       // RED
                {2, 0, 0, 255}, {2, 85, 85, 255}, {2, 60, 68, 170},       // BLUE
                {3, 0, 255, 0}, {3, 85, 255, 85}, {3, 128, 199, 31}, {3, 94, 124, 22}, // GREEN
                {4, 255, 255, 0}, {4, 255, 255, 85}, {4, 254, 216, 61},   // YELLOW
                {5, 0, 255, 255}, {5, 85, 255, 255}, {5, 58, 179, 218}, {5, 22, 156, 156}, // AQUA
                {6, 255, 255, 255}, {6, 240, 240, 240},                   // WHITE
                {7, 255, 85, 255}, {7, 243, 139, 170}, {7, 199, 78, 189}, // PINK
                {8, 128, 128, 128}, {8, 85, 85, 85}, {8, 157, 157, 151}   // GRAY
        };

        int bestTeam = 0;
        int bestDistSq = 9000; // threshold ~95 RGB Euclidean distance
        for (int[] entry : palette) {
            int dr = r - entry[1];
            int dg = g - entry[2];
            int db = b - entry[3];
            int dSq = dr * dr + dg * dg + db * db;
            if (dSq < bestDistSq) {
                bestDistSq = dSq;
                bestTeam = entry[0];
            }
        }
        return bestTeam;
    }

    public static String detectServerProfile(Minecraft mc) {
        if (mc == null || mc.isLocalServer()) return "local";
        ServerData srv = mc.getCurrentServer();
        if (srv == null || srv.ip == null) return "local";
        String ip = srv.ip.toLowerCase(Locale.ROOT);
        if (ip.contains("pika")) return "pika";
        if (ip.contains("jartex")) return "jartex";
        if (ip.contains("mineberry")) return "mineberry";
        if (ip.contains("hypixel")) return "hypixel";
        if (ip.contains("minemen") || ip.contains("mmc")) return "minemen";
        if (ip.contains("localhost") || ip.startsWith("127.") || ip.startsWith("192.168.") || ip.startsWith("10.")) {
            return "local";
        }
        return ip.replaceAll("[^a-z0-9]", "_");
    }

    private static int parseTeamColorLine(String lower) {
        if (lower.contains("red")) return 1;
        if (lower.contains("blue")) return 2;
        if (lower.contains("green") || lower.contains("lime")) return 3;
        if (lower.contains("yellow")) return 4;
        if (lower.contains("aqua") || lower.contains("cyan")) return 5;
        if (lower.contains("white")) return 6;
        if (lower.contains("pink") || lower.contains("magenta")) return 7;
        if (lower.contains("gray") || lower.contains("grey")) return 8;
        return 0;
    }

    public int getOwnTeamId() {
        return ownTeamId;
    }

    public int getEpisodeId() {
        return episodeId;
    }

    public boolean isLastFrameValidInGame() {
        return lastFrameValidInGame;
    }

    public String getDetectedServerProfile() {
        return detectedServerProfile;
    }

    public static int teamColorToTeamId(Optional<?> colorOpt) {
        if (colorOpt == null || colorOpt.isEmpty()) return 0;
        String lower = colorOpt.get().toString().toLowerCase(Locale.ROOT);
        if (lower.contains("purple")) return 7;
        if (lower.contains("gold")) return 4;
        return parseTeamColorLine(lower);
    }

    public String getDetectedMapName() {
        return detectedMapName;
    }

    public int getMatchPhase() {
        return matchPhase;
    }

    public static String stripFormatting(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '§' && i + 1 < s.length()) {
                i++;
            } else {
                out.append(c);
            }
        }
        return out.toString().trim();
    }

    private static double sq(double v) {
        return v * v;
    }
}
