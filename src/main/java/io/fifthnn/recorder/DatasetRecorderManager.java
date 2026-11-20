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
 * Orchestrates Phase 1 dataset recording with bulletproof failsafes:
 *  - Start/Stop anytime via hotkey (F9), GUI, or /5nn command.
 *  - Auto-handles disconnect, client exit, disk-space guard, size rotation, and crash-safe flushing.
 *  - Cleanly tags pause/chat/unfocused/lobby/respawn frames and HG-DAgger live takeovers.
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
        String mapName = "auto".equalsIgnoreCase(config.mapNameOverride)
                ? sanitize(stateTracker.getDetectedMapName())
                : sanitize(config.mapNameOverride);
        String srv = sanitize(config.serverProfile);
        String ts = LocalDateTime.now().format(FILE_TS);
        String filename = String.format("%s_%s_%s_%s.5nn.gz", ts, srv, mapName, mode.name());
        Path outFile = config.getRecordingsDir().resolve(filename);

        Map<String, Object> header = new LinkedHashMap<>();
        header.put("modVersion", "0.1.0");
        header.put("mcVersion", "26.3");
        header.put("recordingMode", mode.name());
        header.put("serverProfile", config.serverProfile);
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
            lastStatusMessage = "Recording: " + filename;
            notifyPlayer(mc, "§a[5ifthNN] Recording STARTED §7(" + mode.displayName + " | " + filename + ")");
            LOGGER.info("Started 5ifthNN recording -> {}", outFile);
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

        String msg = String.format(
                "§e[5ifthNN] Recording STOPPED §7(%s) — %d valid frames (%.1f MB) saved to %s",
                reason,
                w.getValidFramesWritten(),
                w.getBytesWrittenEst() / (1024.0 * 1024.0),
                w.getFilePath().getFileName()
        );
        lastStatusMessage = "Saved " + w.getFilePath().getFileName() + " (" + w.getValidFramesWritten() + " valid ticks)";
        notifyPlayer(mc, msg);
        LOGGER.info("Stopped recording ({}): {} frames ({} valid, {} dropped)",
                reason, w.getFramesWritten(), w.getValidFramesWritten(), w.getDroppedFrames());
    }

    /**
     * Called every 20Hz client tick. Returns the captured FrameData so Phase 2 AI can reuse
     * the exact same snapshot without capturing twice!
     */
    public synchronized FrameData onClientTick(Minecraft mc, boolean aiActive, boolean daggerTakeover) {
        if (mc.player == null || mc.level == null) {
            if (activeWriter != null) {
                stopRecording(mc, "Left world / disconnected");
            }
            return null;
        }

        // Capture only if recording OR AI inference is active (zero idle overhead when both off!)
        if (activeWriter == null && !aiActive) {
            return null;
        }

        FrameData fr = WorldSnapshotCapture.capture(mc, sessionTickIndex, inputTracker, stateTracker, config);
        if (fr == null) return null;

        if (aiActive) fr.qualityFlags |= FifthNNSchema.QFLAG_AI_ACTIVE;
        if (daggerTakeover) fr.qualityFlags |= FifthNNSchema.QFLAG_DAGGER_TAKEOVER;

        if (activeWriter != null) {
            // Failsafe: auto-pause disk writes while window is completely unfocused if configured
            boolean skipWrite = config.autoPauseOnUnfocus && !mc.isWindowActive() && !aiActive;
            if (!skipWrite) {
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
        stateTracker.resetForNewMatch();
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
