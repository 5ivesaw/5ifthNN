package io.fifthnn.recorder;

import io.fifthnn.config.FifthNNConfig;
import io.fifthnn.schema.FifthNNSchema;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Orchestrates Phase 1 dataset recording with bulletproof failsafes & multi-game auto-segmentation:
 *  - Press F9 ONCE and play multiple BedWars matches back-to-back; lobbies, queues, deaths,
 *    pauses, chat, unfocused windows, and server teleports are cleanly tagged.
 *  - Auto-detects server profile (pika/jartex/mineberry/hypixel/local) and map name.
 *  - Prints a detailed session quality summary in chat on stop.
 */
public final class DatasetRecorderManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("5ifthNN-Recorder");
    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final FifthNNConfig config;
    private final InputTracker inputTracker;
    private final BedWarsStateTracker stateTracker;

    private BinaryDatasetWriter activeWriter;
    private long sessionTickIndex = 0;
    private long sessionStartWallMs = 0;
    private String lastStatusMessage = "Idle";

    // Live quality counters for HUD & end-of-session chat scorecard
    private long sessionShopClicks = 0;
    private long sessionBlocksPlaced = 0;
    private long sessionCombatClicks = 0;
    private long sessionLobbyTagged = 0;
    private int startEpisodeId = 1;

    public DatasetRecorderManager(FifthNNConfig config, InputTracker inputTracker, BedWarsStateTracker stateTracker) {
        this.config = config;
        this.inputTracker = inputTracker;
        this.stateTracker = stateTracker;
    }

    public synchronized boolean isRecording() {
        return activeWriter != null && !activeWriter.hasFailed();
    }

    public synchronized void toggleRecording(Minecraft mc) {
        if (isRecording()) {
            stopRecording(mc, "Stopped by user");
        } else {
            startRecording(mc, config.getRecordingModeEnum());
        }
    }

    public synchronized boolean startRecording(Minecraft mc, FifthNNSchema.RecordingMode mode) {
        if (isRecording()) {
            stopRecording(mc, "Restarting session");
        }
        if (mc.player == null || mc.level == null) {
            notifyPlayer(mc, "§c[5ifthNN] Cannot start recording: not in a world!");
            return false;
        }

        config.setRecordingModeEnum(mode);
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
        header.put("modVersion", "0.2.0");
        header.put("mcVersion", "26.3");
        header.put("schemaVersion", FifthNNSchema.SCHEMA_VERSION);
        header.put("recordingMode", mode.name());
        header.put("serverProfile", resolvedServer);
        header.put("mapName", mapName);
        header.put("pvpMode", config.pvpMode18 ? "1.8" : "modern");
        header.put("playerNameHash", mc.player.getGameProfile().name().hashCode());
        header.put("startTimestampMs", System.currentTimeMillis());
        header.put("tickRate", 20);
        header.put("voxelDims", new int[]{FifthNNSchema.RAW_VOXEL_SIZE_XZ, FifthNNSchema.RAW_VOXEL_SIZE_Y, FifthNNSchema.RAW_VOXEL_SIZE_XZ});
        header.put("lidarCount", FifthNNSchema.LIDAR_RAY_COUNT);

        try {
            activeWriter = new BinaryDatasetWriter(
                    outFile, header, config.flushIntervalTicks, config.maxSessionSizeMb, config.minFreeDiskMb
            );
            sessionTickIndex = 0;
            sessionStartWallMs = System.currentTimeMillis();
            sessionShopClicks = 0;
            sessionBlocksPlaced = 0;
            sessionCombatClicks = 0;
            sessionLobbyTagged = 0;
            startEpisodeId = stateTracker.getEpisodeId();

            lastStatusMessage = "Recording: " + filename;
            notifyPlayer(mc, String.format(
                    "§a[5ifthNN] Recording STARTED §7(%s | server=%s | %s)",
                    mode.displayName, resolvedServer, filename
            ));
            LOGGER.info("Started 5ifthNN v0.2 recording -> {}", outFile);
            return true;
        } catch (Exception e) {
            lastStatusMessage = "Start failed: " + e.getMessage();
            notifyPlayer(mc, "§c[5ifthNN] Failsafe blocked recording: " + e.getMessage());
            LOGGER.error("Failed to start recording", e);
            return false;
        }
    }

    public synchronized void stopRecording(Minecraft mc, String reason) {
        if (activeWriter == null) return;
        BinaryDatasetWriter w = activeWriter;
        activeWriter = null;
        w.close();

        long validFrames = w.getValidFramesWritten();
        long totalFrames = Math.max(1L, w.getFramesWritten());
        double validMin = validFrames / 1200.0;
        double filteredPct = (100.0 * sessionLobbyTagged) / totalFrames;
        int episodes = Math.max(1, stateTracker.getEpisodeId() - startEpisodeId + 1);

        String summary = String.format(
                "§e[5ifthNN] STOPPED §7(%s)\n"
                        + "§f  Valid In-Game: §a%.1f min §7(%d ticks, %.0f%% lobby/pause filtered)\n"
                        + "§f  Activity: §b%d ep(s) §7| §e%d shop clicks §7| §a%d blocks §7| §c%d LMB clicks\n"
                        + "§f  File: §7%s (%.2f MB)",
                reason, validMin, validFrames, filteredPct,
                episodes, sessionShopClicks, sessionBlocksPlaced, sessionCombatClicks,
                w.getFilePath().getFileName(), w.getBytesWrittenEst() / (1024.0 * 1024.0)
        );
        lastStatusMessage = String.format("Saved %.1fm valid (%s)", validMin, w.getFilePath().getFileName());
        notifyPlayer(mc, summary);
        LOGGER.info("Stopped recording ({}): {} frames ({} valid, {} dropped, {} shop, {} blocks)",
                reason, w.getFramesWritten(), validFrames, w.getDroppedFrames(), sessionShopClicks, sessionBlocksPlaced);
    }

    /**
     * Called every 20Hz client tick. Returns the captured FrameData so Phase 2 AI reuses
     * the exact same snapshot without capturing twice.
     */
    public synchronized FrameData onClientTick(Minecraft mc, boolean aiActive, boolean daggerTakeover) {
        if (mc.player == null || mc.level == null) {
            if (activeWriter != null) {
                stopRecording(mc, "Left world / disconnected");
            }
            return null;
        }

        if (activeWriter == null && !aiActive) {
            return null;
        }

        FrameData fr = WorldSnapshotCapture.capture(mc, sessionTickIndex, inputTracker, stateTracker, config);
        if (fr == null) return null;

        if (aiActive) fr.qualityFlags |= FifthNNSchema.QFLAG_AI_ACTIVE;
        if (daggerTakeover) fr.qualityFlags |= FifthNNSchema.QFLAG_DAGGER_TAKEOVER;

        if (activeWriter != null) {
            boolean skipWrite = config.autoPauseOnUnfocus && !mc.isWindowActive() && !aiActive;
            if (!skipWrite) {
                if ((fr.qualityFlags & FifthNNSchema.QFLAG_VALID_IN_GAME) != 0) {
                    if (fr.guiClickSlot >= 0) sessionShopClicks++;
                    if ((fr.eventFlagsThisTick & FifthNNSchema.EVT_BLOCK_PLACED) != 0) sessionBlocksPlaced++;
                    sessionCombatClicks += fr.leftClicksThisTick;
                } else {
                    sessionLobbyTagged++;
                }

                boolean ok = activeWriter.enqueueFrame(fr);
                sessionTickIndex++;
                if (!ok || activeWriter.hasFailed()) {
                    String reason = activeWriter.getFailureReason();
                    stopRecording(mc, "Failsafe: " + (reason != null ? reason : "writer error"));
                }
            }
        }
        return fr;
    }

    public synchronized void onDisconnect(Minecraft mc) {
        if (activeWriter != null) {
            stopRecording(mc, "Server disconnect failsafe");
        }
        inputTracker.reset();
        stateTracker.resetForNewMatch(true);
    }

    public synchronized void onClientShutdown() {
        if (activeWriter != null) {
            activeWriter.close();
            activeWriter = null;
        }
    }

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
