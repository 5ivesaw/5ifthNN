package io.fifthnn.recorder;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.fifthnn.config.FifthNNConfig;
import io.fifthnn.schema.FifthNNSchema;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Orchestrates Phase 1 dataset recording (v0.3.0):
 *  - 3-State Zero-Config Auto-Recorder (STANDBY -> ACTIVE_MATCH -> AUTO_PRUNE), plus F9 manual override.
 *  - 40-tick (2s) in-memory pre-write ring buffer that scrubs lagbacks, W->AIR->W block flickers,
 *    and client freezes BEFORE they hit disk while preserving exact tickIndex gaps for GRU resets.
 *  - Append-only `.tags.jsonl` sidecar for PEARL_LANDING, F6 (8s Highlight), and F7 (5s/15s Scrap).
 *  - End-of-match auto-pruning, rolling disk cap, cross-session skill diet stats, and `/5nn share`.
 */
public final class DatasetRecorderManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("5ifthNN-Recorder");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final int RING_BUFFER_TICKS = 40;

    public enum RecorderState {
        STANDBY,
        ACTIVE_MATCH,
        MANUAL_RECORDING
    }

    private final FifthNNConfig config;
    private final InputTracker inputTracker;
    private final BedWarsStateTracker stateTracker;

    private RecorderState state = RecorderState.STANDBY;
    private BinaryDatasetWriter activeWriter;
    private TagSidecarWriter activeTags;
    private final Deque<FrameData> ringBuffer = new ArrayDeque<>(RING_BUFFER_TICKS + 4);

    private boolean stopping = false;
    private long sessionTickIndex = 0;
    private long sessionStartWallMs = 0;
    private int postQuarantineTicksRemaining = 0;
    private String lastStatusMessage = "Standby";

    // Live quality & protocol-health counters
    private long sessionValidWritten = 0;
    private long sessionIdleTicks = 0;
    private long sessionBridgeTicks = 0;
    private long sessionPvpTicks = 0;
    private long sessionShopClicks = 0;
    private long sessionBlocksPlaced = 0;
    private long sessionCombatClicks = 0;
    private long sessionLobbySkipped = 0;
    private long sessionQuarantinedTicks = 0;
    private int sessionLagbacks = 0;
    private int sessionFlickers = 0;
    private int startEpisodeId = 1;

    // F7 double-tap detection, Auto-Requeue & HUD toast
    private long lastScrapTapMs = 0L;
    private String toastText = null;
    private long toastExpireMs = 0L;
    private int standbyPollCounter = 0;
    private long pendingRequeueAtMs = -1L;
    private final java.util.Random rng = new java.util.Random();

    // Cached cross-session totals from .meta.json files (zero per-tick disk I/O)
    private double totalSavedMinutes = 0.0;
    private long totalSavedShopClicks = 0;
    private long totalSavedBlocks = 0;
    private int totalSavedMatches = 0;

    public DatasetRecorderManager(FifthNNConfig config, InputTracker inputTracker, BedWarsStateTracker stateTracker) {
        this.config = config;
        this.inputTracker = inputTracker;
        this.stateTracker = stateTracker;
        refreshDatasetTotals();
    }

    public synchronized boolean isRecording() {
        return activeWriter != null && !activeWriter.hasFailed();
    }

    public synchronized RecorderState getRecorderState() {
        return state;
    }

    public synchronized void toggleRecording(Minecraft mc) {
        if (isRecording()) {
            stopRecording(mc, "Stopped by user", false);
        } else {
            startRecording(mc, config.getRecordingModeEnum(), true);
        }
    }

    public synchronized boolean startRecording(Minecraft mc, FifthNNSchema.RecordingMode mode) {
        return startRecording(mc, mode, true);
    }

    public synchronized boolean startRecording(Minecraft mc, FifthNNSchema.RecordingMode mode, boolean manual) {
        if (isRecording()) {
            stopRecording(mc, "Restarting session", false);
        }
        if (mc.player == null || mc.level == null) {
            if (manual) notifyPlayer(mc, "§c[5ifthNN] Cannot start recording: not in a world!");
            return false;
        }

        if (manual) {
            config.setRecordingModeEnum(mode);
        }
        stateTracker.preCaptureUpdate(mc, mc.player);

        String resolvedServer = "auto".equalsIgnoreCase(config.serverProfile)
                ? stateTracker.getDetectedServerProfile()
                : config.serverProfile;
        String mapName = "auto".equalsIgnoreCase(config.mapNameOverride)
                ? sanitize(stateTracker.getDetectedMapName())
                : sanitize(config.mapNameOverride);
        String srv = sanitize(resolvedServer);
        String ts = LocalDateTime.now().format(FILE_TS);
        String filename = String.format("%s_%s_%s_%s.5nn.gz", ts, srv, mapName, mode.name());
        Path outFile = config.getRecordingsDir().resolve(filename);

        Map<String, Object> header = new LinkedHashMap<>();
        header.put("modVersion", "0.5.1");
        header.put("mcVersion", "26.3");
        header.put("schemaVersion", FifthNNSchema.SCHEMA_VERSION);
        header.put("recordingMode", mode.name());
        header.put("autoRecorded", !manual);
        header.put("serverProfile", resolvedServer);
        header.put("mapName", mapName);
        header.put("pvpMode", config.pvpMode18 ? "1.8" : "modern");
        header.put("viaFabricPlusLoaded", ViaFlickerGuard.isViaFabricPlusLoaded());
        header.put("playerNameHash", mc.player.getGameProfile().name().hashCode());
        if (mc.options != null) {
            header.put("fov", mc.options.fov().get());
            header.put("mouseSensitivity", mc.options.sensitivity().get());
        }
        header.put("startTimestampMs", System.currentTimeMillis());
        header.put("tickRate", 20);
        header.put("voxelDims", new int[]{FifthNNSchema.RAW_VOXEL_SIZE_XZ, FifthNNSchema.RAW_VOXEL_SIZE_Y, FifthNNSchema.RAW_VOXEL_SIZE_XZ});
        header.put("lidarCount", FifthNNSchema.LIDAR_RAY_COUNT);

        try {
            activeWriter = new BinaryDatasetWriter(
                    outFile, header, config.flushIntervalTicks, config.maxSessionSizeMb, config.minFreeDiskMb
            );
            activeTags = new TagSidecarWriter(outFile);
            ringBuffer.clear();

            sessionTickIndex = 0;
            sessionStartWallMs = System.currentTimeMillis();
            postQuarantineTicksRemaining = 0;
            sessionValidWritten = 0;
            sessionIdleTicks = 0;
            sessionBridgeTicks = 0;
            sessionPvpTicks = 0;
            sessionShopClicks = 0;
            sessionBlocksPlaced = 0;
            sessionCombatClicks = 0;
            sessionLobbySkipped = 0;
            sessionQuarantinedTicks = 0;
            sessionLagbacks = 0;
            sessionFlickers = 0;
            startEpisodeId = stateTracker.getEpisodeId();
            stateTracker.getFlickerMonitor().reset();

            state = manual ? RecorderState.MANUAL_RECORDING : RecorderState.ACTIVE_MATCH;
            lastStatusMessage = (manual ? "REC: " : "AUTO: ") + filename;
            if (manual) {
                notifyPlayer(mc, String.format(
                        "§a[5ifthNN] Recording STARTED §7(%s | server=%s | %s)",
                        mode.displayName, resolvedServer, filename
                ));
            } else {
                showToast("● Auto-recording " + mapName);
            }
            LOGGER.info("Started 5ifthNN v0.3 recording (manual={}) -> {}", manual, outFile);
            return true;
        } catch (Exception e) {
            state = RecorderState.STANDBY;
            lastStatusMessage = "Start failed: " + e.getMessage();
            if (manual) {
                notifyPlayer(mc, "§c[5ifthNN] Failsafe blocked recording: " + e.getMessage());
            }
            LOGGER.error("Failed to start recording", e);
            return false;
        }
    }

    public synchronized void stopRecording(Minecraft mc, String reason) {
        stopRecording(mc, reason, state == RecorderState.ACTIVE_MATCH);
    }

    private synchronized void stopRecording(Minecraft mc, String reason, boolean allowAutoPrune) {
        if (activeWriter == null || stopping) {
            if (activeWriter == null) state = RecorderState.STANDBY;
            return;
        }
        stopping = true;
        try {
            // Flush any remaining clean frames from the 40-tick ring buffer before closing
            flushRingBufferAll();

            BinaryDatasetWriter w = activeWriter;
            TagSidecarWriter t = activeTags;
            boolean wasManual = (state == RecorderState.MANUAL_RECORDING);
            activeWriter = null;
            activeTags = null;
            state = RecorderState.STANDBY;

            w.close();
            if (t != null) t.close();

            long validFrames = w.getValidFramesWritten();
            double validSec = validFrames / 20.0;
            double validMin = validFrames / 1200.0;
            double idlePct = validFrames > 0 ? (100.0 * sessionIdleTicks) / validFrames : 100.0;
            double flickerRate = sessionBlocksPlaced >= 10 ? ((double) sessionFlickers / sessionBlocksPlaced) : 0.0;
            double lagbacksPerMin = validMin > 0.25 ? (sessionLagbacks / validMin) : 0.0;

            // End-of-match auto-prune for useless / severely corrupted auto-recorded matches
            if (allowAutoPrune) {
                String pruneReason = null;
                if (validSec < 20.0) {
                    pruneReason = "too short (<20s)";
                } else if (idlePct > 75.0) {
                    pruneReason = String.format("mostly AFK (%.0f%% idle)", idlePct);
                } else if (flickerRate > 0.30 || lagbacksPerMin > 8.0) {
                    pruneReason = String.format("severe lag/flickers (FLK=%d, RB=%d)", sessionFlickers, sessionLagbacks);
                    notifyPlayer(mc, "§e[5ifthNN] Skipped laggy match (" + pruneReason + ") — use ViaFabricPlus 1.8.x!");
                }
                if (pruneReason != null) {
                    deleteRecordingBundle(w.getFilePath());
                    lastStatusMessage = "Auto-pruned (" + pruneReason + ")";
                    LOGGER.info("Auto-pruned recording {} ({})", w.getFilePath().getFileName(), pruneReason);
                    return;
                }
            }

            // Compute quality grade & enrich .meta.json sidecar
            String grade = computeQualityGrade(validSec, idlePct, flickerRate, lagbacksPerMin);
            enrichMetaSidecar(w.getFilePath(), grade, validMin, idlePct);
            enforceRollingStorageCap();
            refreshDatasetTotals();

            int episodes = Math.max(1, stateTracker.getEpisodeId() - startEpisodeId + 1);
            lastStatusMessage = String.format("Saved Grade %s (%.1fm)", grade, validMin);
            if (wasManual) {
                String summary = String.format(
                        "§e[5ifthNN] STOPPED §7(%s) §f[Grade %s]\n"
                                + "§f  Valid In-Game: §a%.1f min §7(%d ticks | skipped=%d | quarantined=%d)\n"
                                + "§f  Activity: §b%d ep(s) §7| §e%d shop §7| §a%d blocks §7| §c%d LMB §7| §6RB:%d FLK:%d\n"
                                + "§f  File: §7%s (%.2f MB)",
                        reason, grade, validMin, validFrames, sessionLobbySkipped, sessionQuarantinedTicks,
                        episodes, sessionShopClicks, sessionBlocksPlaced, sessionCombatClicks,
                        sessionLagbacks, sessionFlickers,
                        w.getFilePath().getFileName(), w.getBytesWrittenEst() / (1024.0 * 1024.0)
                );
                notifyPlayer(mc, summary);
            } else {
                double bridgePct = validFrames > 0 ? (100.0 * sessionBridgeTicks) / validFrames : 0.0;
                double pvpPct = validFrames > 0 ? (100.0 * sessionPvpTicks) / validFrames : 0.0;
                showToast(String.format("✓ Grade %s (%.1fm)", grade, validMin));
                notifyPlayer(mc, String.format(
                        "§a[5ifthNN] Saved Grade %s §7(%.1fm | Bridge %.0f%% | PvP %.0f%% | Shop %d | Blocks %d)",
                        grade, validMin, bridgePct, pvpPct, sessionShopClicks, sessionBlocksPlaced
                ));
                if (config.autoRequeueEnabled && config.autoRequeueCommand != null && !config.autoRequeueCommand.isBlank()) {
                    pendingRequeueAtMs = System.currentTimeMillis() + 2500L + rng.nextInt(2000); // 2.5-4.5s random delay
                }
            }
            LOGGER.info("Stopped recording ({}) [Grade {}]: {} valid frames, RB={}, FLK={}",
                    reason, grade, validFrames, sessionLagbacks, sessionFlickers);
        } finally {
            stopping = false;
        }
    }

    /**
     * Called every 20Hz client tick. Manages automatic STANDBY <-> ACTIVE_MATCH transitions,
     * captures FrameData once (shared with Phase 2 AI), and runs the 40-tick pre-write ring buffer.
     */
    public synchronized FrameData onClientTick(Minecraft mc, boolean aiActive, boolean daggerTakeover) {
        if (mc.player == null || mc.level == null) {
            if (activeWriter != null) {
                stopRecording(mc, "Left world / disconnected", state == RecorderState.ACTIVE_MATCH);
            }
            return null;
        }

        // 0. Passive Auto-Requeue timer (runs ONLY after AUTO_STOP closed .5nn.gz; cancels if GUI/ESC opens!)
        if (pendingRequeueAtMs > 0L) {
            if (mc.gui.screen() != null || isRecording()) {
                pendingRequeueAtMs = -1L;
            } else if (System.currentTimeMillis() >= pendingRequeueAtMs) {
                pendingRequeueAtMs = -1L;
                String cmd = config.autoRequeueCommand.trim();
                if (cmd.startsWith("/")) cmd = cmd.substring(1);
                if (!cmd.isEmpty() && mc.player.connection != null) {
                    mc.player.connection.sendCommand(cmd);
                    showToast("↻ Auto-requeue sent");
                }
            }
        }

        // 1. Automatic STANDBY -> ACTIVE_MATCH check (zero disk I/O while in hubs / other minigames)
        if (activeWriter == null && !aiActive) {
            if (config.autoRecordBedWars) {
                standbyPollCounter++;
                if (standbyPollCounter >= 5) {
                    standbyPollCounter = 0;
                    stateTracker.preCaptureUpdate(mc, mc.player);
                    boolean inGameStandby = stateTracker.getMatchPhase() == FifthNNSchema.MATCH_PHASE_IN_GAME;
                    stateTracker.getLandmarkCache().tickScan(mc.player, mc.level, inGameStandby);
                    if (stateTracker.isConfidentBedWarsMatch(mc, mc.player)) {
                        startRecording(mc, FifthNNSchema.RecordingMode.FULL_MATCH, false);
                    }
                }
            }
            if (activeWriter == null) {
                return null;
            }
        }

        // 2. Capture full 1:1 FrameData with strictly monotonic sessionTickIndex!
        long thisTick = sessionTickIndex;
        FrameData fr = WorldSnapshotCapture.capture(mc, thisTick, inputTracker, stateTracker, config);
        if (fr == null) return null;

        if (aiActive) fr.qualityFlags |= FifthNNSchema.QFLAG_AI_ACTIVE;
        if (daggerTakeover) fr.qualityFlags |= FifthNNSchema.QFLAG_DAGGER_TAKEOVER;

        // 3. Auto-stop immediately on Victory / Elimination / Hub warp (zero victory-dance frames!)
        if (state == RecorderState.ACTIVE_MATCH && stateTracker.shouldAutoStopMatch(mc.player)) {
            stopRecording(mc, "Match ended", true);
            return fr;
        }

        if (activeWriter != null) {
            // CRITICAL INVARIANT #3: sessionTickIndex ALWAYS increments on every tick so dropped
            // ticks leave a real tick_index gap (> 2) and split_contiguous_segments() resets GRU!
            sessionTickIndex++;

            boolean flickerNow = stateTracker.getFlickerMonitor().tickCheck(mc.level, stateTracker.getTrackerTickClock());
            boolean lagbackNow = stateTracker.isLagbackThisTick();
            boolean pearlNow = stateTracker.isPearlLandingThisTick();
            boolean lagSpikeNow = stateTracker.isLagSpikeThisTick();

            if (pearlNow && activeTags != null) {
                activeTags.writeTag(thisTick - 1, thisTick + 1, fr.episodeId, FifthNNSchema.TAG_PEARL_LANDING);
            }

            if (lagbackNow) {
                sessionLagbacks++;
                scrubRingBufferBack(thisTick - 30L);
                postQuarantineTicksRemaining = Math.max(postQuarantineTicksRemaining, 15);
                if (activeTags != null) {
                    activeTags.writeTag(thisTick - 30L, thisTick + 15L, fr.episodeId, FifthNNSchema.TAG_LAGBACK_QUARANTINE);
                }
                showToast("⚠ Auto-quarantined lagback");
            }

            if (flickerNow) {
                sessionFlickers++;
                scrubRingBufferBack(thisTick - 5L);
                postQuarantineTicksRemaining = Math.max(postQuarantineTicksRemaining, 20);
                if (activeTags != null) {
                    activeTags.writeTag(thisTick - 5L, thisTick + 20L, fr.episodeId, FifthNNSchema.TAG_FLICKER_QUARANTINE);
                }
                if (stateTracker.getFlickerMonitor().shouldShowHighFlickerHint()) {
                    notifyPlayer(mc, "§e[5ifthNN] High block-flicker rate detected — install ViaFabricPlus (set server to 1.8.x)!");
                }
            }

            if (lagSpikeNow) {
                scrubRingBufferBack(thisTick - 2L);
                postQuarantineTicksRemaining = Math.max(postQuarantineTicksRemaining, 10);
                if (activeTags != null) {
                    activeTags.writeTag(thisTick - 2L, thisTick + 10L, fr.episodeId, FifthNNSchema.TAG_LAG_SPIKE);
                }
            }

            if (postQuarantineTicksRemaining > 0) {
                postQuarantineTicksRemaining--;
                sessionQuarantinedTicks++;
                return fr;
            }

            // Skip non-gameplay frames BEFORE disk write (dead/pause/chat/Alt-Tab/lobby write 0 bytes!)
            boolean isValidGameplay = (fr.qualityFlags & FifthNNSchema.QFLAG_VALID_IN_GAME) != 0;
            boolean unfocusedSkip = config.autoPauseOnUnfocus && !mc.isWindowActive() && !aiActive;
            if (!isValidGameplay || unfocusedSkip) {
                sessionLobbySkipped++;
                return fr;
            }

            // Enqueue clean gameplay frame into 40-tick pre-write ring buffer
            ringBuffer.addLast(fr);
            while (ringBuffer.size() > RING_BUFFER_TICKS) {
                FrameData ready = ringBuffer.pollFirst();
                if (ready != null && !commitFrameToWriter(mc, ready)) {
                    break;
                }
            }
        }
        return fr;
    }

    private void scrubRingBufferBack(long minTickToDrop) {
        if (ringBuffer.isEmpty()) return;
        int before = ringBuffer.size();
        ringBuffer.removeIf(f -> f.tickIndex >= minTickToDrop);
        sessionQuarantinedTicks += (before - ringBuffer.size());
    }

    private void flushRingBufferAll() {
        while (!ringBuffer.isEmpty() && activeWriter != null && !activeWriter.hasFailed()) {
            FrameData ready = ringBuffer.pollFirst();
            if (ready != null) {
                commitFrameToWriter(null, ready);
            }
        }
    }

    private boolean commitFrameToWriter(Minecraft mc, FrameData fr) {
        if (activeWriter == null) return false;
        sessionValidWritten++;
        if (fr.keyMask == 0 && Math.abs(fr.deltaYaw) < 0.05f && Math.abs(fr.deltaPitch) < 0.05f) {
            sessionIdleTicks++;
        }
        if ((fr.keyMask & FifthNNSchema.KEY_SNEAK) != 0 && fr.pitch > 55.0f) {
            sessionBridgeTicks++;
        }
        if (fr.leftClicksThisTick > 0) {
            sessionPvpTicks++;
            sessionCombatClicks += fr.leftClicksThisTick;
        }
        if (fr.guiClickSlot >= 0) sessionShopClicks++;
        if ((fr.eventFlagsThisTick & FifthNNSchema.EVT_BLOCK_PLACED) != 0) sessionBlocksPlaced++;

        boolean ok = activeWriter.enqueueFrame(fr);
        if (!ok || activeWriter.hasFailed()) {
            String reason = activeWriter.getFailureReason();
            stopRecording(mc, "Failsafe: " + (reason != null ? reason : "writer error"), false);
            return false;
        }
        return true;
    }

    /** F6: Bookmark last 8 seconds as high-value clean play (2.5x BC weight). */
    public synchronized void onHighlightKey(Minecraft mc) {
        if (!isRecording() || activeTags == null) {
            showToast("Not recording");
            return;
        }
        long end = sessionTickIndex;
        activeTags.writeTag(end - 160L, end, stateTracker.getEpisodeId(), FifthNNSchema.TAG_USER_HIGHLIGHT_8S);
        showToast("★ Highlighted last 8s");
    }

    /** F7: Single-tap scraps last 3s (60t); double-tap within 600ms upgrades to last 10s (200t). */
    public synchronized void onScrapKey(Minecraft mc) {
        if (!isRecording() || activeTags == null) {
            showToast("Not recording");
            return;
        }
        long now = System.currentTimeMillis();
        long end = sessionTickIndex;
        boolean doubleTap = (now - lastScrapTapMs) <= 600L;
        lastScrapTapMs = now;
        if (doubleTap) {
            scrubRingBufferBack(end - 200L);
            activeTags.writeTag(end - 200L, end, stateTracker.getEpisodeId(), FifthNNSchema.TAG_USER_SCRAP_10S);
            showToast("✂ Scrapped last 10s");
        } else {
            scrubRingBufferBack(end - 60L);
            activeTags.writeTag(end - 60L, end, stateTracker.getEpisodeId(), FifthNNSchema.TAG_USER_SCRAP_3S);
            showToast("✂ Scrapped last 3s (tap again for 10s)");
        }
    }

    public synchronized void scrapCurrentEpisode(Minecraft mc) {
        if (!isRecording() || activeTags == null) {
            notifyPlayer(mc, "§7[5ifthNN] No active recording to scrap.");
            return;
        }
        int ep = stateTracker.getEpisodeId();
        ringBuffer.clear();
        activeTags.writeTag(0L, sessionTickIndex, ep, FifthNNSchema.TAG_USER_SCRAP_EPISODE);
        showToast("✂ Scrapped Ep#" + ep);
        notifyPlayer(mc, "§e[5ifthNN] Marked Episode #" + ep + " as scrapped (recording continues).");
    }

    /** `/5nn share` or F8 "Eval & Pack Dataset" — evaluates all recordings and bundles Grade A/B into 1 Colab-ready .zip. */
    public synchronized Path exportShareZip(Minecraft mc) {
        Path recDir = config.getRecordingsDir();
        String ts = LocalDateTime.now().format(FILE_TS);
        Path zipPath = config.getRootDir().resolve("5ifthNN_dataset_" + ts + ".zip");
        int kept = 0;
        int skipped = 0;
        double totalMin = 0.0;
        List<String> includedFiles = new ArrayList<>();
        try {
            List<Path> recs = new ArrayList<>();
            try (var stream = Files.list(recDir)) {
                stream.filter(p -> p.getFileName().toString().endsWith(".5nn.gz")).sorted().forEach(recs::add);
            }
            try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipPath))) {
                for (Path rec : recs) {
                    Path meta = Path.of(rec.toString() + ".meta.json");
                    String grade = "A";
                    double mins = 0.0;
                    double idlePct = 0.0;
                    if (Files.exists(meta)) {
                        try {
                            JsonObject obj = JsonParser.parseString(Files.readString(meta)).getAsJsonObject();
                            if (obj.has("qualityGrade")) grade = obj.get("qualityGrade").getAsString();
                            if (obj.has("validInGameFrames")) mins = obj.get("validInGameFrames").getAsLong() / 1200.0;
                            if (obj.has("idlePct")) idlePct = obj.get("idlePct").getAsDouble();
                        } catch (Exception ignored) {}
                    }
                    // Evaluator filter: keep only Grade A/B with >= 20s valid gameplay and < 65% idle
                    if ("C".equalsIgnoreCase(grade) || (Files.exists(meta) && (mins < 0.33 || idlePct > 65.0))) {
                        skipped++;
                        continue;
                    }

                    addToZip(zos, rec);
                    if (Files.exists(meta)) addToZip(zos, meta);
                    Path tags = Path.of(rec.toString() + ".tags.jsonl");
                    if (Files.exists(tags)) addToZip(zos, tags);
                    includedFiles.add(rec.getFileName().toString());
                    kept++;
                    totalMin += mins;
                }
                if (kept > 0) {
                    Map<String, Object> manifest = new LinkedHashMap<>();
                    manifest.put("createdAt", ts);
                    manifest.put("keptMatches", kept);
                    manifest.put("skippedLowQuality", skipped);
                    manifest.put("totalValidMinutes", Math.round(totalMin * 100.0) / 100.0);
                    manifest.put("files", includedFiles);
                    zos.putNextEntry(new ZipEntry("manifest.json"));
                    zos.write(GSON.toJson(manifest).getBytes(StandardCharsets.UTF_8));
                    zos.closeEntry();
                }
            }
            if (kept == 0) {
                Files.deleteIfExists(zipPath);
                notifyPlayer(mc, "§e[5ifthNN] Evaluator found 0 clean Grade A/B recordings to pack (skipped=" + skipped + ").");
                return null;
            }
            double mb = Files.size(zipPath) / (1024.0 * 1024.0);
            notifyPlayer(mc, String.format(
                    "§a[5ifthNN] Packed %d clean match(es) §7(skipped %d low-quality | %.1f min | %.2f MB) -> §f%s",
                    kept, skipped, totalMin, mb, zipPath.getFileName()
            ));
            return zipPath;
        } catch (Exception e) {
            notifyPlayer(mc, "§c[5ifthNN] Dataset pack failed: " + e.getMessage());
            LOGGER.error("Dataset pack failed", e);
            return null;
        }
    }

    private static void addToZip(ZipOutputStream zos, Path file) throws IOException {
        zos.putNextEntry(new ZipEntry(file.getFileName().toString()));
        Files.copy(file, zos);
        zos.closeEntry();
    }

    private static String computeQualityGrade(double validSec, double idlePct, double flickerRate, double lagbacksPerMin) {
        if (validSec >= 60.0 && idlePct < 40.0 && flickerRate < 0.08 && lagbacksPerMin < 2.0) return "A";
        if (validSec >= 25.0 && idlePct < 60.0 && flickerRate < 0.20 && lagbacksPerMin < 5.0) return "B";
        return "C";
    }

    private void enrichMetaSidecar(Path recFile, String grade, double validMin, double idlePct) {
        Path metaPath = Path.of(recFile.toString() + ".meta.json");
        try {
            JsonObject obj = Files.exists(metaPath)
                    ? JsonParser.parseString(Files.readString(metaPath)).getAsJsonObject()
                    : new JsonObject();
            obj.addProperty("qualityGrade", grade);
            obj.addProperty("validMinutes", Math.round(validMin * 100.0) / 100.0);
            obj.addProperty("idlePct", Math.round(idlePct * 10.0) / 10.0);
            obj.addProperty("shopClicks", sessionShopClicks);
            obj.addProperty("blocksPlaced", sessionBlocksPlaced);
            obj.addProperty("bridgeTicks", sessionBridgeTicks);
            obj.addProperty("pvpTicks", sessionPvpTicks);
            obj.addProperty("lagbacks", sessionLagbacks);
            obj.addProperty("flickers", sessionFlickers);
            Files.writeString(metaPath, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("Failed to enrich meta sidecar: {}", e.getMessage());
        }
    }

    private void enforceRollingStorageCap() {
        long capBytes = Math.max(64L, config.maxRecordingsFolderMb) * 1024L * 1024L;
        try {
            List<Path> files = new ArrayList<>();
            long total = 0L;
            try (var stream = Files.list(config.getRecordingsDir())) {
                for (Path p : stream.toList()) {
                    if (p.getFileName().toString().endsWith(".5nn.gz")) {
                        files.add(p);
                        total += Files.size(p);
                    }
                }
            }
            if (total <= capBytes) return;
            files.sort(Comparator.comparing(p -> p.getFileName().toString())); // oldest first
            for (Path oldest : files) {
                if (total <= capBytes) break;
                long sz = Files.exists(oldest) ? Files.size(oldest) : 0L;
                deleteRecordingBundle(oldest);
                total -= sz;
                LOGGER.info("Rolling storage cap pruned oldest recording: {}", oldest.getFileName());
            }
        } catch (Exception e) {
            LOGGER.warn("Rolling storage cap check failed: {}", e.getMessage());
        }
    }

    private static void deleteRecordingBundle(Path recPath) {
        try { Files.deleteIfExists(recPath); } catch (IOException ignored) {}
        try { Files.deleteIfExists(Path.of(recPath.toString() + ".meta.json")); } catch (IOException ignored) {}
        try { Files.deleteIfExists(Path.of(recPath.toString() + ".tags.jsonl")); } catch (IOException ignored) {}
    }

    public synchronized void refreshDatasetTotals() {
        double mins = 0.0;
        long shops = 0;
        long blocks = 0;
        int matches = 0;
        try (var stream = Files.list(config.getRecordingsDir())) {
            for (Path p : stream.toList()) {
                if (!p.getFileName().toString().endsWith(".meta.json")) continue;
                try {
                    JsonObject obj = JsonParser.parseString(Files.readString(p)).getAsJsonObject();
                    if (obj.has("validInGameFrames")) mins += obj.get("validInGameFrames").getAsLong() / 1200.0;
                    if (obj.has("shopClicks")) shops += obj.get("shopClicks").getAsLong();
                    if (obj.has("blocksPlaced")) blocks += obj.get("blocksPlaced").getAsLong();
                    matches++;
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        totalSavedMinutes = mins;
        totalSavedShopClicks = shops;
        totalSavedBlocks = blocks;
        totalSavedMatches = matches;
    }

    public synchronized void onDisconnect(Minecraft mc) {
        if (activeWriter != null) {
            stopRecording(mc, "Server disconnect", state == RecorderState.ACTIVE_MATCH);
        }
        ViaFlickerGuard.clear();
        inputTracker.reset();
        stateTracker.resetForNewMatch(true);
    }

    public synchronized void onClientShutdown() {
        if (activeWriter != null) {
            flushRingBufferAll();
            activeWriter.close();
            activeWriter = null;
        }
        if (activeTags != null) {
            activeTags.close();
            activeTags = null;
        }
        state = RecorderState.STANDBY;
    }

    private void showToast(String msg) {
        this.toastText = msg;
        this.toastExpireMs = System.currentTimeMillis() + 2500L;
    }

    public String getActiveToast() {
        return (toastText != null && System.currentTimeMillis() <= toastExpireMs) ? toastText : null;
    }

    public boolean isQuarantinedNow() {
        return postQuarantineTicksRemaining > 0;
    }

    public int getSessionLagbacks() { return sessionLagbacks; }
    public int getSessionFlickers() { return sessionFlickers; }
    public double getTotalSavedMinutes() { return totalSavedMinutes; }
    public long getTotalSavedShopClicks() { return totalSavedShopClicks; }
    public long getTotalSavedBlocks() { return totalSavedBlocks; }
    public int getTotalSavedMatches() { return totalSavedMatches; }

    public long getElapsedSeconds() {
        if (!isRecording() || sessionStartWallMs == 0) return 0;
        return (System.currentTimeMillis() - sessionStartWallMs) / 1000L;
    }

    public long getValidFramesWritten() {
        return activeWriter != null ? activeWriter.getValidFramesWritten() : 0;
    }

    public long getTotalFramesWritten() {
        return activeWriter != null ? activeWriter.getFramesWritten() : 0;
    }

    public long getBytesWrittenEst() {
        return activeWriter != null ? activeWriter.getBytesWrittenEst() : 0;
    }

    public String getLastStatusMessage() {
        return lastStatusMessage;
    }

    private static String sanitize(String raw) {
        if (raw == null || raw.isBlank()) return "unknown";
        return raw.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    private static void notifyPlayer(Minecraft mc, String message) {
        if (mc != null && mc.player != null) {
            mc.player.sendSystemMessage(Component.literal(message));
        }
    }
}
