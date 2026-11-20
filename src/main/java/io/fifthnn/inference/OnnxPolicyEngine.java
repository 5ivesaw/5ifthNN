package io.fifthnn.inference;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import io.fifthnn.schema.FifthNNSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Phase 2 Embedded ONNX Runtime Inference Engine for 5ifthNN.
 *
 * Loads any exported `.onnx` model from `.minecraft/config/5ifthnn/models/`,
 * maintains the Two-Timescale GRU hidden states (h_fast @ 20Hz, h_slow @ 2Hz),
 * runs forward passes in ~1.5-2.5ms on CPU, and enforces NaN/Inf failsafes.
 */
public final class OnnxPolicyEngine implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("5ifthNN-ONNX");

    private OrtEnvironment env;
    private OrtSession session;
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

    public synchronized boolean loadModel(Path modelPath) {
        unload();
        if (modelPath == null || !Files.exists(modelPath)) {
            lastError = "Model file not found: " + modelPath;
            return false;
        }
        try {
            env = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
            opts.setIntraOpNumThreads(2);
            opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            session = env.createSession(modelPath.toAbsolutePath().toString(), opts);
            loadedModelPath = modelPath;
            resetHiddenStates();
            lastError = null;
            LOGGER.info("Loaded 5ifthNN ONNX model: {} (inputs={}, outputs={})",
                    modelPath.getFileName(), session.getInputNames(), session.getOutputNames());
            return true;
        } catch (Throwable t) {
            lastError = "ONNX load failed: " + t.getMessage();
            LOGGER.error(lastError, t);
            unload();
            return false;
        }
    }

    public synchronized void unload() {
        if (session != null) {
            try {
                session.close();
            } catch (Exception ignored) {}
            session = null;
        }
        loadedModelPath = null;
        resetHiddenStates();
    }

    public synchronized void resetHiddenStates() {
        Arrays.fill(hFast, 0.0f);
        Arrays.fill(hSlow, 0.0f);
        stepCounter = 0;
    }

    public synchronized boolean isLoaded() {
        return session != null;
    }

    public synchronized String getLoadedModelName() {
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

    /**
     * Execute one 20 Hz forward pass. Returns null on NaN/Inf or runtime error so
     * the caller can immediately trigger the safety interlock!
     */
    public synchronized PolicyDecision infer(
            FeatureEncoder.EncodedFeatures feats,
            boolean guiOpen,
            int validGuiSlots,
            float temperature,
            boolean stochastic
    ) {
        if (session == null || env == null) return null;

        long t0 = System.nanoTime();
        float updSlowVal = (stepCounter % FifthNNSchema.SLOW_UPDATE_INTERVAL == 0) ? 1.0f : 0.0f;

        try (OnnxTensor vcT = OnnxTensor.createTensor(env, LongBuffer.wrap(feats.voxelCats()),
                     new long[]{1, FifthNNSchema.NN_VOXEL_SIZE_Y, FifthNNSchema.NN_VOXEL_SIZE_XZ, FifthNNSchema.NN_VOXEL_SIZE_XZ});
             OnnxTensor vfT = OnnxTensor.createTensor(env, FloatBuffer.wrap(feats.voxelFlags()),
                     new long[]{1, 2, FifthNNSchema.NN_VOXEL_SIZE_Y, FifthNNSchema.NN_VOXEL_SIZE_XZ, FifthNNSchema.NN_VOXEL_SIZE_XZ});
             OnnxTensor obsT = OnnxTensor.createTensor(env, FloatBuffer.wrap(feats.scalarObs()),
                     new long[]{1, FifthNNSchema.NN_SCALAR_OBS_DIM});
             OnnxTensor hfT = OnnxTensor.createTensor(env, FloatBuffer.wrap(hFast),
                     new long[]{1, FifthNNSchema.FAST_HIDDEN_DIM});
             OnnxTensor hsT = OnnxTensor.createTensor(env, FloatBuffer.wrap(hSlow),
                     new long[]{1, FifthNNSchema.SLOW_HIDDEN_DIM});
             OnnxTensor updT = OnnxTensor.createTensor(env, FloatBuffer.wrap(new float[]{updSlowVal}),
                     new long[]{1, 1})) {

            Map<String, OnnxTensor> inputs = new HashMap<>(8);
            inputs.put("voxel_cats", vcT);
            inputs.put("voxel_flags", vfT);
            inputs.put("scalar_obs", obsT);
            inputs.put("h_fast", hfT);
            inputs.put("h_slow", hsT);
            inputs.put("update_slow", updT);

            try (OrtSession.Result res = session.run(inputs)) {
                float[] moveLogits = extract1D(res.get(0).getValue());
                float[] yawLogits = extract1D(res.get(1).getValue());
                float[] pitchLogits = extract1D(res.get(2).getValue());
                float[] leftLogits = extract1D(res.get(3).getValue());
                float[] rightLogits = extract1D(res.get(4).getValue());
                float[] hotbarLogits = extract1D(res.get(5).getValue());
                float[] guiLogits = extract1D(res.get(6).getValue());
                float[] valArr = extract1D(res.get(7).getValue());
                float[] nextFast = extract1D(res.get(8).getValue());
                float[] nextSlow = extract1D(res.get(9).getValue());

                // Safety check: abort immediately if model outputs NaN or Inf
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

                // Prevent contradictory movement pairs (W+S or A+D)
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
            }
        } catch (Throwable t) {
            lastError = "ONNX inference error: " + t.getMessage();
            LOGGER.error(lastError, t);
            return null;
        }
    }

    private static float[] extract1D(Object val) {
        if (val instanceof float[][] arr2 && arr2.length > 0) {
            return arr2[0];
        }
        if (val instanceof float[] arr1) {
            return arr1;
        }
        return new float[0];
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
