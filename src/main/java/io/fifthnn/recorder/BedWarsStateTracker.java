package io.fifthnn.recorder;

import io.fifthnn.schema.FifthNNSchema;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
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
 * Tracks live BedWars match state, player-placed blocks, one-time milestone rewards,
 * chat/scoreboard events, and server TPS across PikaNetwork, Hypixel, and local BedWars plugins.
 */
public final class BedWarsStateTracker {
    private final Set<Long> playerPlacedBlocks = new HashSet<>(4096);

    private int matchPhase = FifthNNSchema.MATCH_PHASE_IN_GAME;
    private int ownTeamId = 0;
    private int ownBedAlive = 1;
    private int enemyBedsAliveMask = 0x7F;
    private int enemiesAliveCount = 7;
    private final byte[] teamUpgrades = new byte[5]; // sharp, prot, haste, forge, trap
    private int diamondTier = 1;
    private int emeraldTier = 1;
    private int matchElapsedTicks = 0;

    private String detectedMapName = "unknown";
    private float homeX, homeY, homeZ;
    private boolean hasHomeSpawn = false;

    private int pendingEventFlags = 0;
    private float pendingReward = 0.0f;

    private float prevHp = 20.0f;
    private boolean wasDead = false;
    private long lastTickWallMs = 0L;
    private float serverTpsEst = 20.0f;

    public synchronized void resetForNewMatch() {
        playerPlacedBlocks.clear();
        ownBedAlive = 1;
        enemyBedsAliveMask = 0x7F;
        enemiesAliveCount = 7;
        for (int i = 0; i < 5; i++) teamUpgrades[i] = 0;
        diamondTier = 1;
        emeraldTier = 1;
        matchElapsedTicks = 0;
        hasHomeSpawn = false;
        pendingEventFlags = 0;
        pendingReward = 0.0f;
    }

    public synchronized void markBlockPlaced(BlockPos pos) {
        playerPlacedBlocks.add(pos.asLong());
        pendingEventFlags |= FifthNNSchema.EVT_BLOCK_PLACED;
    }

    public synchronized void markBlockBroken(BlockPos pos) {
        playerPlacedBlocks.remove(pos.asLong());
        pendingEventFlags |= FifthNNSchema.EVT_BLOCK_BROKEN;
    }

    public synchronized boolean isTrackedPlayerPlaced(BlockPos pos) {
        return playerPlacedBlocks.contains(pos.asLong());
    }

    public synchronized void markItemBought() {
        pendingEventFlags |= FifthNNSchema.EVT_ITEM_BOUGHT;
    }

    public synchronized void markDamageDealt() {
        pendingEventFlags |= FifthNNSchema.EVT_DAMAGE_DEALT;
    }

    /**
     * Parse system/game chat messages (works across BedWars1058, ScreamingBedWars, Hypixel, PikaNetwork).
     */
    public synchronized void onChatMessage(String rawText, String selfName) {
        if (rawText == null || rawText.isEmpty()) return;
        String lower = stripFormatting(rawText).toLowerCase(Locale.ROOT);
        String selfLower = selfName != null ? selfName.toLowerCase(Locale.ROOT) : "";

        // Match start / lobby detection
        if (lower.contains("protect your bed and destroy the enemy beds")
                || lower.contains("game has started")
                || lower.contains("bed wars")) {
            if (lower.contains("protect your bed")) {
                resetForNewMatch();
                matchPhase = FifthNNSchema.MATCH_PHASE_IN_GAME;
            }
        }
        if (lower.contains("waiting for players") || lower.contains("the game starts in")) {
            matchPhase = FifthNNSchema.MATCH_PHASE_LOBBY;
        }
        if (lower.contains("victory!") || lower.contains("won the game")) {
            matchPhase = FifthNNSchema.MATCH_PHASE_POST_GAME;
            if (!selfLower.isEmpty() && lower.contains(selfLower)) {
                pendingEventFlags |= FifthNNSchema.EVT_GAME_WON;
                pendingReward += 5.0f;
            }
        }
        if (lower.contains("game over!") || lower.contains("you have been eliminated")) {
            matchPhase = FifthNNSchema.MATCH_PHASE_SPECTATING;
            pendingEventFlags |= FifthNNSchema.EVT_GAME_LOST;
            pendingReward -= 5.0f;
        }

        // Bed break events (one-time irreversible milestones)
        if (lower.contains("bed destruction") || lower.contains("bed was destroyed") || lower.contains("bed has been destroyed")) {
            if (lower.contains("your bed")) {
                ownBedAlive = 0;
                pendingEventFlags |= FifthNNSchema.EVT_OWN_BED_LOST;
                pendingReward -= 1.5f;
            } else if (!selfLower.isEmpty() && lower.contains(selfLower)) {
                pendingEventFlags |= FifthNNSchema.EVT_BED_BROKEN_BY_ME;
                pendingReward += 2.0f;
            }
        }

        // Kill / final kill events
        if (!selfLower.isEmpty() && (lower.contains("killed by " + selfLower) || lower.contains("knocked into the void by " + selfLower))) {
            if (lower.contains("final kill")) {
                pendingEventFlags |= FifthNNSchema.EVT_FINAL_KILL;
                pendingReward += 1.5f;
            } else {
                pendingEventFlags |= FifthNNSchema.EVT_KILL;
                pendingReward += 0.05f;
            }
        }

        // Upgrades & generator tiers
        if (lower.contains("sharpened swords")) teamUpgrades[0] = (byte) Math.min(4, teamUpgrades[0] + 1);
        if (lower.contains("reinforced armor")) teamUpgrades[1] = (byte) Math.min(4, teamUpgrades[1] + 1);
        if (lower.contains("maniac miner")) teamUpgrades[2] = (byte) Math.min(4, teamUpgrades[2] + 1);
        if (lower.contains("iron forge") || lower.contains("gold forge") || lower.contains("molten forge")) {
            teamUpgrades[3] = (byte) Math.min(4, teamUpgrades[3] + 1);
        }
        if (lower.contains("purchased") && lower.contains("trap")) teamUpgrades[4] = (byte) Math.min(3, teamUpgrades[4] + 1);
        if (lower.contains("diamond generators have been upgraded to tier ii")) diamondTier = 2;
        if (lower.contains("diamond generators have been upgraded to tier iii")) diamondTier = 3;
        if (lower.contains("emerald generators have been upgraded to tier ii")) emeraldTier = 2;
        if (lower.contains("emerald generators have been upgraded to tier iii")) emeraldTier = 3;
    }

    public synchronized void tickAndFillFrame(Minecraft mc, LocalPlayer player, FrameData fr, boolean autoTagLobby) {
        // Estimate server TPS via wall-clock tick interval
        long now = System.currentTimeMillis();
        if (lastTickWallMs > 0) {
            long dt = now - lastTickWallMs;
            if (dt > 5 && dt < 2000) {
                float instTps = Math.min(20.0f, 1000.0f / dt);
                serverTpsEst = 0.9f * serverTpsEst + 0.1f * instTps;
            }
        }
        lastTickWallMs = now;
        fr.serverTpsEst = serverTpsEst;

        // Parse scoreboard sidebar for team color, map name, bed counts, and lobby heuristics
        parseScoreboard(mc, player);
        if (ownTeamId == 0 && player.getTeam() != null) {
            ownTeamId = teamColorToTeamId(player.getTeam().getColor());
        }

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
        if (deadNow && !wasDead) {
            pendingEventFlags |= FifthNNSchema.EVT_DEATH;
            if (player.getY() < 10.0) {
                pendingEventFlags |= FifthNNSchema.EVT_VOID_FALL;
            }
        }
        wasDead = deadNow;

        if (player.isSpectator()) {
            fr.matchPhase = deadNow ? FifthNNSchema.MATCH_PHASE_RESPAWNING : FifthNNSchema.MATCH_PHASE_SPECTATING;
        } else if (deadNow) {
            fr.matchPhase = FifthNNSchema.MATCH_PHASE_RESPAWNING;
        } else {
            fr.matchPhase = matchPhase;
        }

        if (fr.matchPhase == FifthNNSchema.MATCH_PHASE_IN_GAME) {
            matchElapsedTicks++;
            pendingReward -= 0.0005f; // tiny per-tick time pressure against base camping
        }

        // Copy state into FrameData
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

        if (hasHomeSpawn) {
            fr.landmarks.add(new FrameData.LandmarkEntry(
                    FifthNNSchema.LM_HOME_SPAWN, ownTeamId, homeX, homeY, homeZ, ownBedAlive
            ));
        }

        // Compute quality flags (failsafe noise tagging!)
        int qFlags = 0;
        boolean focused = mc.isWindowActive();
        if (focused) qFlags |= FifthNNSchema.QFLAG_WINDOW_FOCUSED;
        boolean pauseOrChat = (fr.guiType == FifthNNSchema.GUI_PAUSE || fr.guiType == FifthNNSchema.GUI_CHAT);
        if (pauseOrChat) qFlags |= FifthNNSchema.QFLAG_PAUSE_OR_CHAT;
        if (deadNow || fr.matchPhase == FifthNNSchema.MATCH_PHASE_RESPAWNING) qFlags |= FifthNNSchema.QFLAG_DEAD_OR_RESPAWN;
        boolean lobbyOrSpec = (fr.matchPhase == FifthNNSchema.MATCH_PHASE_LOBBY
                || fr.matchPhase == FifthNNSchema.MATCH_PHASE_SPECTATING
                || fr.matchPhase == FifthNNSchema.MATCH_PHASE_POST_GAME);
        if (lobbyOrSpec) qFlags |= FifthNNSchema.QFLAG_LOBBY_OR_SPEC;

        boolean valid = !pauseOrChat && !deadNow && focused && (!autoTagLobby || !lobbyOrSpec);
        if (valid) qFlags |= FifthNNSchema.QFLAG_VALID_IN_GAME;
        fr.qualityFlags = qFlags;

        // Clear per-tick accumulators
        pendingEventFlags = 0;
        pendingReward = 0.0f;
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
            if (lower.contains("waiting...") || lower.contains("starting in")) {
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

        if (sawLobbyLine) {
            matchPhase = FifthNNSchema.MATCH_PHASE_LOBBY;
        } else if (sawBedwarsTeams) {
            matchPhase = FifthNNSchema.MATCH_PHASE_IN_GAME;
            enemyBedsAliveMask = livingEnemyBeds;
            enemiesAliveCount = livingTeams;
        }
    }

    private static int parseTeamColorLine(String lower) {
        if (lower.contains("red")) return 1;
        if (lower.contains("blue")) return 2;
        if (lower.contains("green")) return 3;
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

    public static int teamColorToTeamId(Optional<?> colorOpt) {
        if (colorOpt == null || colorOpt.isEmpty()) return 0;
        String lower = colorOpt.get().toString().toLowerCase(Locale.ROOT);
        if (lower.contains("purple")) return 7; // LIGHT_PURPLE / DARK_PURPLE -> Pink/Magenta team
        if (lower.contains("gold")) return 4;   // GOLD -> Yellow team
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
}
