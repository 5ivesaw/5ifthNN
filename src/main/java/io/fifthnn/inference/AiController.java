package io.fifthnn.inference;

import io.fifthnn.config.FifthNNConfig;
import io.fifthnn.recorder.FrameData;
import io.fifthnn.schema.FifthNNSchema;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Phase 2 AI Controller: manages ONNX model hot-loading, local-server safety interlock,
 * HG-DAgger live human takeover, GRU hidden-state resets, and emergency kill-switch.
 */
public final class AiController {
    private static final Logger LOGGER = LoggerFactory.getLogger("5ifthNN-AI");

    private final FifthNNConfig config;
    private final OnnxPolicyEngine engine;
    private final HumanActionExecutor executor;

    private FifthNNSchema.AiControlMode mode = FifthNNSchema.AiControlMode.OFF;
    private boolean takeoverActive = false;
    private boolean wasDead = false;
    private String lastSafetyNote = "AI Off";

    public AiController(FifthNNConfig config, OnnxPolicyEngine engine, HumanActionExecutor executor) {
        this.config = config;
        this.engine = engine;
        this.executor = executor;
    }

    public List<String> listAvailableModels() {
        List<String> out = new ArrayList<>();
        try (var stream = Files.list(config.getModelsDir())) {
            stream.filter(p -> p.getFileName().toString().endsWith(".onnx"))
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .forEach(out::add);
        } catch (Exception ignored) {}
        return out;
    }

    public boolean loadSelectedModel(Minecraft mc, String filename) {
        config.selectedModelFile = filename;
        config.save();
        Path target = config.getModelsDir().resolve(filename);
        boolean ok = engine.loadModel(target);
        if (ok) {
            lastSafetyNote = "Loaded " + filename;
            notify(mc, "§a[5ifthNN] Loaded ONNX model: §f" + filename);
        } else {
            lastSafetyNote = engine.getLastError() != null ? engine.getLastError() : "Load failed";
            notify(mc, "§c[5ifthNN] Failed to load ONNX model: " + lastSafetyNote);
        }
        return ok;
    }

    public void setMode(Minecraft mc, FifthNNSchema.AiControlMode newMode) {
        if (newMode == FifthNNSchema.AiControlMode.OFF) {
            emergencyStop(mc, "Turned OFF");
            return;
        }
        if (config.localServerSafetyLock && !isLocalOrSingleplayer(mc)) {
            lastSafetyNote = "Blocked: Public server safety lock enabled";
            notify(mc, "§c[5ifthNN] Safety Lock: AI control is restricted to localhost/private training servers.");
            return;
        }
        if (!engine.isLoaded()) {
            if (!loadSelectedModel(mc, config.selectedModelFile)) {
                notify(mc, "§c[5ifthNN] Put an exported .onnx model in .minecraft/config/5ifthnn/models/ first!");
                return;
            }
        }
        engine.resetHiddenStates();
        this.mode = newMode;
        this.takeoverActive = false;
        this.lastSafetyNote = "Active (" + newMode.displayName + ")";
        notify(mc, "§b[5ifthNN] Phase 2 AI ENGAGED: §f" + newMode.displayName + " §7(Press F10 anytime to emergency stop)");
    }

    public void toggleAi(Minecraft mc) {
        if (mode != FifthNNSchema.AiControlMode.OFF) {
            emergencyStop(mc, "Emergency Stop (F10)");
        } else {
            setMode(mc, FifthNNSchema.AiControlMode.INFER);
        }
    }

    public void emergencyStop(Minecraft mc, String reason) {
        mode = FifthNNSchema.AiControlMode.OFF;
        takeoverActive = false;
        executor.releaseAllControls(mc);
        engine.resetHiddenStates();
        lastSafetyNote = reason;
        notify(mc, "§e[5ifthNN] AI DISENGAGED §7(" + reason + ")");
    }

    public boolean isAiEnabled() {
        return mode != FifthNNSchema.AiControlMode.OFF;
    }

    public boolean isTakeoverActive() {
        return takeoverActive;
    }

    public FifthNNSchema.AiControlMode getMode() {
        return mode;
    }

    public String getLastSafetyNote() {
        return lastSafetyNote;
    }

    public OnnxPolicyEngine getEngine() {
        return engine;
    }

    public HumanActionExecutor getExecutor() {
        return executor;
    }

    public void onClientTick(Minecraft mc, FrameData frame, boolean takeoverKeyHeld) {
        if (mode == FifthNNSchema.AiControlMode.OFF) {
            takeoverActive = false;
            return;
        }
        if (mc.player == null || mc.level == null || frame == null) {
            emergencyStop(mc, "World unloaded");
            return;
        }

        // Public-server interlock guard even if player switches servers mid-session
        if (config.localServerSafetyLock && !isLocalOrSingleplayer(mc)) {
            emergencyStop(mc, "Switched to non-local server");
            return;
        }

        // Auto-reset GRU memory on death/respawn so stale combat context does not bleed over
        boolean deadNow = !mc.player.isAlive() || mc.player.getHealth() <= 0.0f;
        if (deadNow && !wasDead) {
            executor.releaseAllControls(mc);
            engine.resetHiddenStates();
        }
        wasDead = deadNow;
        if (deadNow || mc.player.isSpectator()) {
            executor.releaseAllControls(mc);
            return;
        }

        String srvProfile = "auto".equalsIgnoreCase(config.serverProfile)
                ? io.fifthnn.recorder.BedWarsStateTracker.detectServerProfile(mc)
                : config.serverProfile;

        // HG-DAgger Live Human Takeover: holding takeover key immediately yields 100% control to human
        if (takeoverKeyHeld) {
            if (!takeoverActive) {
                executor.releaseAllControls(mc);
                takeoverActive = true;
            }
            // Still step hidden states passively so memory stays synced when human releases control!
            var feats = FeatureEncoder.encode(frame, FifthNNSchema.serverProfileToId(srvProfile), config.pvpMode18);
            engine.infer(feats, frame.guiType > 0, frame.guiSlotCount, config.samplingTemperature, false);
            return;
        }
        takeoverActive = false;

        // Run Pure NN forward pass
        var feats = FeatureEncoder.encode(frame, FifthNNSchema.serverProfileToId(srvProfile), config.pvpMode18);
        var decision = engine.infer(
                feats,
                frame.guiType > 0,
                frame.guiSlotCount,
                config.samplingTemperature,
                config.stochasticSampling
        );

        if (decision == null) {
            emergencyStop(mc, "ONNX safety interlock (" + engine.getLastError() + ")");
            return;
        }

        if (decision.latencyMs() > 15.0) {
            LOGGER.warn("5ifthNN ONNX latency spike: {} ms", String.format("%.2f", decision.latencyMs()));
        }

        executor.execute(mc, decision, config);
    }

    public void onDisconnect(Minecraft mc) {
        if (mode != FifthNNSchema.AiControlMode.OFF) {
            emergencyStop(mc, "Disconnected");
        }
    }

    private static boolean isLocalOrSingleplayer(Minecraft mc) {
        if (mc.isLocalServer()) return true;
        ServerData srv = mc.getCurrentServer();
        if (srv == null) return true;
        String ip = srv.ip.toLowerCase(Locale.ROOT);
        return ip.startsWith("localhost") || ip.startsWith("127.") || ip.startsWith("192.168.")
                || ip.startsWith("10.") || ip.startsWith("0.0.0.0") || ip.equals("::1");
    }

    private static void notify(Minecraft mc, String msg) {
        if (mc != null && mc.player != null) {
            mc.player.sendSystemMessage(Component.literal(msg));
        }
    }
}
