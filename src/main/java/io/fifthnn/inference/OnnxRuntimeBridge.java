package io.fifthnn.inference;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import io.fifthnn.schema.FifthNNSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Isolated ONNX Runtime JNI bridge.
 *
 * Kept strictly in its own class so {@link OnnxPolicyEngine} never loads any {@code ai.onnxruntime.*}
 * bytecode unless the optional {@code 5ifthNN-OnnxRuntime} companion JAR is installed in {@code mods/}.
 */
final class OnnxRuntimeBridge implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("5ifthNN-ONNX");

    private OrtEnvironment env;
    private OrtSession session;

    record RawOutputs(
            float[] moveLogits,
            float[] yawLogits,
            float[] pitchLogits,
            float[] leftLogits,
            float[] rightLogits,
            float[] hotbarLogits,
            float[] guiLogits,
            float[] valArr,
            float[] nextFast,
            float[] nextSlow
    ) {}

    void load(Path modelPath) throws Exception {
        close();
        env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        opts.setIntraOpNumThreads(2);
        opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        session = env.createSession(modelPath.toAbsolutePath().toString(), opts);
        LOGGER.info("Loaded 5ifthNN ONNX model: {} (inputs={}, outputs={})",
                modelPath.getFileName(), session.getInputNames(), session.getOutputNames());
    }

    boolean isLoaded() {
        return session != null && env != null;
    }

    RawOutputs run(FeatureEncoder.EncodedFeatures feats, float[] hFast, float[] hSlow, float updSlowVal) throws Exception {
        if (session == null || env == null) return null;

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
                return new RawOutputs(
                        extract1D(res.get(0).getValue()),
                        extract1D(res.get(1).getValue()),
                        extract1D(res.get(2).getValue()),
                        extract1D(res.get(3).getValue()),
                        extract1D(res.get(4).getValue()),
                        extract1D(res.get(5).getValue()),
                        extract1D(res.get(6).getValue()),
                        extract1D(res.get(7).getValue()),
                        extract1D(res.get(8).getValue()),
                        extract1D(res.get(9).getValue())
                );
            }
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

    @Override
    public void close() {
        if (session != null) {
            try {
                session.close();
            } catch (Exception ignored) {}
            session = null;
        }
    }
}
