package io.fifthnn.inference;

import io.fifthnn.schema.FifthNNSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;

/**
 * Phase 2 Embedded ONNX Runtime Inference Engine for 5ifthNN (v0.3).
 *
 * Gracefully supports running 5ifthNN standalone (~150 KB Phase 1 recorder JAR) WITHOUT
 * ONNX Runtime installed; once the one-time {@code 5ifthNN-OnnxRuntime} companion JAR is
 * placed in {@code mods/}, loads any exported `.onnx` model from `.minecraft/config/5ifthnn/models/`.
 */
public final class OnnxPolicyEngine implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("5ifthNN-ONNX");
    private static final boolean RUNTIME_AVAILABLE = checkRuntimeAvailable();

    private Object bridgeObj; // lazily instantiated OnnxRuntimeBridge only when RUNTIME_AVAILABLE == true
    private Path loadedModelPath;

    private final float[] hFast = new float[FifthNNSchema.FAST_HIDDEN_DIM];
    private final float[] hSlow = new float[FifthNNSchema.SLOW_HIDDEN_DIM];
    private long stepCounter = 0;
    private double lastLatencyMs = 0.0;
    private double emaLatencyMs = 0.0;
    private String lastError = null;
    private final Random rng = new Random();

    public record PolicyDecision(
            boolean w, boolean a, boolean s, boolean d,
            boolean jump, boolean sneak, boolean sprint,
            float deltaYawDeg,
            float deltaPitchDeg,
            int leftState,   // 0=none, 1=tap, 2=hold
            int rightState,  // 0=none, 1=tap, 2=hold
            int hotbarSlot,  // -1=keep, 0..8=slot
            int guiAction,   // 0=noop, 1=close_gui, 2..55=slot 0..53
            float valueEst,
            double latencyMs
    ) {}

    private static boolean checkRuntimeAvailable() {
        try {
            Class.forName("ai.onnxruntime.OrtEnvironment", false, OnnxPolicyEngine.class.getClassLoader());
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean isRuntimeAvailable() {
        return RUNTIME_AVAILABLE;
    }

    public synchronized boolean loadModel(Path modelPath) {
        unload();
        if (!RUNTIME_AVAILABLE) {
            lastError = "Install 5ifthNN-OnnxRuntime-1.32.0.jar in mods/ to enable Phase 2 AI";
            return false;
        }
        if (modelPath == null || !Files.exists(modelPath)) {
            lastError = "Model file not found: " + modelPath;
            return false;
        }
        try {
            OnnxRuntimeBridge bridge = new OnnxRuntimeBridge();
            bridge.load(modelPath);
            this.bridgeObj = bridge;
            this.loadedModelPath = modelPath;
            resetHiddenStates();
            lastError = null;
            return true;
        } catch (Throwable t) {
            lastError = "ONNX load failed: " + t.getMessage();
            LOGGER.error(lastError, t);
            unload();
            return false;
        }
    }

    public synchronized void unload() {
        if (bridgeObj instanceof AutoCloseable ac) {
            try {
                ac.close();
            } catch (Exception ignored) {}
        }
        bridgeObj = null;
        loadedModelPath = null;
        resetHiddenStates();
    }

    public synchronized void resetHiddenStates() {
        Arrays.fill(hFast, 0.0f);
        Arrays.fill(hSlow, 0.0f);
        stepCounter = 0;
    }

    public synchronized boolean isLoaded() {
        return RUNTIME_AVAILABLE && bridgeObj instanceof OnnxRuntimeBridge b && b.isLoaded();
    }

    public synchronized String getLoadedModelName() {
        if (!RUNTIME_AVAILABLE) return "Recorder-Only";
        return loadedModelPath != null ? loadedModelPath.getFileName().toString() : "None";
    }

    public double getLastLatencyMs() {
        return lastLatencyMs;
    }

    public double getEmaLatencyMs() {
        return emaLatencyMs;
    }

    public String getLastError() {
        return lastError;
    }

    public synchronized PolicyDecision infer(
            FeatureEncoder.EncodedFeatures feats,
            boolean guiOpen,
            int validGuiSlots,
            float temperature,
            boolean stochastic
    ) {
        if (!RUNTIME_AVAILABLE || !(bridgeObj instanceof OnnxRuntimeBridge bridge) || !bridge.isLoaded()) {
            return null;
        }

        long t0 = System.nanoTime();
        float updSlowVal = (stepCounter % FifthNNSchema.SLOW_UPDATE_INTERVAL == 0) ? 1.0f : 0.0f;

        try {
            OnnxRuntimeBridge.RawOutputs raw = bridge.run(feats, hFast, hSlow, updSlowVal);
            if (raw == null) return null;

            float[] moveLogits = raw.moveLogits();
            float[] yawLogits = raw.yawLogits();
            float[] pitchLogits = raw.pitchLogits();
            float[] leftLogits = raw.leftLogits();
            float[] rightLogits = raw.rightLogits();
            float[] hotbarLogits = raw.hotbarLogits();
            float[] guiLogits = raw.guiLogits();
            float[] valArr = raw.valArr();
            float[] nextFast = raw.nextFast();
            float[] nextSlow = raw.nextSlow();

            if (!isFinite(moveLogits) || !isFinite(yawLogits) || !isFinite(nextFast)) {
                lastError = "ONNX emitted NaN/Inf — safety interlock tripped!";
                LOGGER.error(lastError);
                return null;
            }

            System.arraycopy(nextFast, 0, hFast, 0, Math.min(hFast.length, nextFast.length));
            System.arraycopy(nextSlow, 0, hSlow, 0, Math.min(hSlow.length, nextSlow.length));
            stepCounter++;

            float temp = Math.max(0.05f, temperature);
            boolean[] keys = new boolean[FifthNNSchema.NUM_MOVEMENT_KEYS];
            for (int i = 0; i < keys.length; i++) {
                float prob = sigmoid(moveLogits[i] / temp);
                keys[i] = stochastic ? (rng.nextFloat() < prob) : (prob >= 0.5f);
            }

            if (keys[0] && keys[2]) {
                if (moveLogits[0] >= moveLogits[2]) keys[2] = false;
                else keys[0] = false;
            }
            if (keys[1] && keys[3]) {
                if (moveLogits[1] >= moveLogits[3]) keys[3] = false;
                else keys[1] = false;
            }

            int yawBin = sampleCategorical(yawLogits, temp, stochastic, yawLogits.length);
            int pitchBin = sampleCategorical(pitchLogits, temp, stochastic, pitchLogits.length);
            float dYaw = FifthNNSchema.dequantizeAngleMuLaw(yawBin, FifthNNSchema.MAX_YAW_DEG_PER_TICK);
            float dPitch = FifthNNSchema.dequantizeAngleMuLaw(pitchBin, FifthNNSchema.MAX_PITCH_DEG_PER_TICK);

            int leftState = sampleCategorical(leftLogits, temp, stochastic, leftLogits.length);
            int rightState = sampleCategorical(rightLogits, temp, stochastic, rightLogits.length);
            int hotbarIdx = sampleCategorical(hotbarLogits, temp, stochastic, hotbarLogits.length);
            int hotbarSlot = (hotbarIdx == 0) ? -1 : (hotbarIdx - 1);

            int guiAction = 0;
            if (guiOpen) {
                int allowed = Math.min(guiLogits.length, 2 + Math.max(0, validGuiSlots));
                guiAction = sampleCategorical(guiLogits, temp, stochastic, allowed);
            }

            lastLatencyMs = (System.nanoTime() - t0) / 1_000_000.0;
            emaLatencyMs = (emaLatencyMs == 0.0) ? lastLatencyMs : (0.9 * emaLatencyMs + 0.1 * lastLatencyMs);

            return new PolicyDecision(
                    keys[0], keys[1], keys[2], keys[3], keys[4], keys[5], keys[6],
                    dYaw, dPitch, leftState, rightState, hotbarSlot, guiAction,
                    valArr.length > 0 ? valArr[0] : 0.0f,
                    lastLatencyMs
            );
        } catch (Throwable t) {
            lastError = "ONNX inference error: " + t.getMessage();
            LOGGER.error(lastError, t);
            return null;
        }
    }

    private static boolean isFinite(float[] arr) {
        for (float v : arr) {
            if (!Float.isFinite(v)) return false;
        }
        return true;
    }

    private static float sigmoid(float x) {
        return (float) (1.0 / (1.0 + Math.exp(-Math.max(-20.0, Math.min(20.0, x)))));
    }

    private int sampleCategorical(float[] logits, float temp, boolean stochastic, int limit) {
        int n = Math.min(logits.length, Math.max(1, limit));
        if (!stochastic) {
            int best = 0;
            for (int i = 1; i < n; i++) {
                if (logits[i] > logits[best]) best = i;
            }
            return best;
        }
        float maxL = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            float v = logits[i] / temp;
            if (v > maxL) maxL = v;
        }
        double sum = 0.0;
        double[] probs = new double[n];
        for (int i = 0; i < n; i++) {
            probs[i] = Math.exp((logits[i] / temp) - maxL);
            sum += probs[i];
        }
        double r = rng.nextDouble() * sum;
        double acc = 0.0;
        for (int i = 0; i < n; i++) {
            acc += probs[i];
            if (r <= acc) return i;
        }
        return n - 1;
    }

    @Override
    public void close() {
        unload();
    }
}
