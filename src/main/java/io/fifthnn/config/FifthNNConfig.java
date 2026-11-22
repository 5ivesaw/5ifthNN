package io.fifthnn.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.fifthnn.schema.FifthNNSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persistent configuration for 5ifthNN saved under `.minecraft/config/5ifthnn/config.json`.
 */
public final class FifthNNConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("5ifthNN-Config");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // Phase 1: Dataset Recorder settings
    public String recordingMode = FifthNNSchema.RecordingMode.FULL_MATCH.name();
    public String serverProfile = "auto";
    public String mapNameOverride = "auto";
    public boolean pvpMode18 = true;
    public boolean recordObserverEntities = true;
    public boolean autoRecordBedWars = true;
    public boolean autoTagLobbyInvalid = true;
    public boolean autoPauseOnUnfocus = true;
    public boolean showHudOverlay = true;
    public boolean minimalHudMode = true;
    public boolean enableViaFlickerGuard = true;
    public boolean autoSetViaFabricPlus18 = true;

    // Smart dependency prompt state (never nags every launch)
    public long lastDependencyPromptMs = 0L;
    public boolean suppressDependencyPrompt = false;

    // Failsafe thresholds
    public long minFreeDiskMb = 1024L;
    public long maxSessionSizeMb = 512L;
    public long maxRecordingsFolderMb = 500L;
    public int flushIntervalTicks = 40; // sync-flush every 2 seconds for crash safety

    // Phase 2: ONNX Inference & Human Physics Guardrails (calibrated to empirical human profile)
    public String selectedModelFile = "fifthnn_v1.onnx";
    public boolean localServerSafetyLock = true;
    public int maxHumanCps = 11;
    public float maxYawDegPerTick = 32.0f;
    public float maxPitchDegPerTick = 22.0f;
    public float samplingTemperature = 0.85f;
    public float aimSmoothingAlpha = 0.70f;
    public boolean enableMouseGcdSmoothing = true;
    public boolean stochasticSampling = true;

    private transient Path rootDir;

    public static FifthNNConfig load(Path configRoot) {
        Path dir = configRoot.resolve("5ifthnn");
        Path file = dir.resolve("config.json");
        FifthNNConfig cfg = new FifthNNConfig();
        try {
            Files.createDirectories(dir);
            Files.createDirectories(dir.resolve("recordings"));
            Files.createDirectories(dir.resolve("models"));
            if (Files.exists(file)) {
                FifthNNConfig loaded = GSON.fromJson(Files.readString(file), FifthNNConfig.class);
                if (loaded != null) {
                    cfg = loaded;
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to load 5ifthNN config, using defaults: {}", e.getMessage());
        }
        cfg.rootDir = dir;
        cfg.save();
        return cfg;
    }

    public void save() {
        if (rootDir == null) return;
        try {
            Files.createDirectories(rootDir);
            Files.writeString(rootDir.resolve("config.json"), GSON.toJson(this));
        } catch (IOException e) {
            LOGGER.warn("Failed to save 5ifthNN config: {}", e.getMessage());
        }
    }

    public Path getRootDir() {
        return rootDir;
    }

    public Path getRecordingsDir() {
        return rootDir.resolve("recordings");
    }

    public Path getModelsDir() {
        return rootDir.resolve("models");
    }

    public FifthNNSchema.RecordingMode getRecordingModeEnum() {
        return FifthNNSchema.RecordingMode.fromName(recordingMode);
    }

    public void setRecordingModeEnum(FifthNNSchema.RecordingMode mode) {
        this.recordingMode = mode.name();
        save();
    }
}
