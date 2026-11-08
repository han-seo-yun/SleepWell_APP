package app.sleepwell.tabdpt;

import android.content.Context;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.FloatBuffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

public final class TabDptMobileRuntime implements AutoCloseable {
    private final BundleAssets assets;
    private final OrtEnvironment environment;
    private final OrtSession.SessionOptions options;
    private final OrtSession session;
    private final Cohort cmi, mesa;
    private final int contextSize, bins, dropped;
    private final float binMin, binMax;
    final long materializeNanos, sessionLoadNanos, contextLoadNanos;
    final String manifestSha256;

    public TabDptMobileRuntime(Context context) throws Exception {
        assets = new BundleAssets(context);
        manifestSha256 = assets.manifestSha256;
        JSONObject manifest = assets.manifest;
        contextSize = manifest.getInt("context_size");
        JSONObject contract = manifest.getJSONObject("model_contract");
        if (!"deterministic_mobile_preprocessed".equals(contract.optString("x_src_semantics")))
            throw new IllegalStateException("unsupported x_src preprocessing contract");
        if (contract.optInt("n_thinking_rows", -1) != 64
                || contract.optString("thinking_embed_sha256").length() != 64)
            throw new IllegalStateException("missing pretrained thinking-row contract");
        bins = contract.getInt("regression_bins");
        dropped = contract.getInt("classification_outputs_to_drop");
        binMin = (float)contract.getDouble("regression_bin_min");
        binMax = (float)contract.getDouble("regression_bin_max");
        long start = System.nanoTime();
        String modelName = manifest.getString("model_file");
        File model = assets.materialize(modelName);
        if (assets.hashes.containsKey(modelName + ".data")) assets.materialize(modelName + ".data");
        materializeNanos = System.nanoTime() - start;
        environment = OrtEnvironment.getEnvironment("sleepwell-tabdpt-mobile");
        environment.setTelemetry(false);
        options = new OrtSession.SessionOptions();
        options.setIntraOpNumThreads(4);
        options.setInterOpNumThreads(1);
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT);
        options.setDeterministicCompute(true);
        start = System.nanoTime();
        session = environment.createSession(model.getAbsolutePath(), options);
        sessionLoadNanos = System.nanoTime() - start;
        start = System.nanoTime();
        JSONObject cohorts = manifest.getJSONObject("cohorts");
        cmi = loadCohort("cmi", cohorts.getJSONObject("cmi"));
        mesa = loadCohort("mesa", cohorts.getJSONObject("mesa"));
        contextLoadNanos = System.nanoTime() - start;
        if (!session.getInputNames().contains("x_src") || !session.getInputNames().contains("y_src")
                || !session.getOutputNames().contains("logits")) throw new IllegalStateException("ONNX contract mismatch");
    }

    public double[] predictAll(double[] cmiRaw39, double[] mesaRaw39) throws Exception {
        double[] raw = predictAllRaw(cmiRaw39, mesaRaw39);
        double[] cmiResult = constrain(new float[]{(float)raw[0], (float)raw[1]});
        double[] mesaResult = constrain(new float[]{(float)raw[2], (float)raw[3]});
        return new double[]{cmiResult[0], cmiResult[1], mesaResult[0], mesaResult[1]};
    }

    double[] predictAllRaw(double[] cmiRaw39, double[] mesaRaw39) throws Exception {
        float[] cmiResult = predict(cmi, cmiRaw39);
        float[] mesaResult = predict(mesa, mesaRaw39);
        return new double[]{cmiResult[0], cmiResult[1], mesaResult[0], mesaResult[1]};
    }

    int[][] debugRetrieval(double[] cmiRaw39, double[] mesaRaw39) {
        float[] cmiQuery = cmi.preprocessor.transform(cmiRaw39);
        float[] mesaQuery = mesa.preprocessor.transform(mesaRaw39);
        return new int[][]{cmi.context.retrieve(cmiQuery, contextSize), mesa.context.retrieve(mesaQuery, contextSize)};
    }

    String[][] debugPreprocessedHashes(double[] cmiRaw39, double[] mesaRaw39) throws Exception {
        return new String[][]{preprocessedHashes(cmi, cmiRaw39), preprocessedHashes(mesa, mesaRaw39)};
    }

    String[] debugTransformedHashes(double[] cmiRaw39, double[] mesaRaw39) throws Exception {
        return new String[]{hashFloats(cmi.preprocessor.transform(cmiRaw39)),
                hashFloats(mesa.preprocessor.transform(mesaRaw39))};
    }

    String[][] debugYHashes(double[] cmiRaw39, double[] mesaRaw39) throws Exception {
        return new String[][]{yHashes(cmi, cmiRaw39), yHashes(mesa, mesaRaw39)};
    }

    private String[] yHashes(Cohort cohort, double[] raw) throws Exception {
        float[] query = cohort.preprocessor.transform(raw);
        int[] indices = cohort.context.retrieve(query, contextSize);
        return new String[]{hashFloats(normalize(cohort, indices, 0).values),
                hashFloats(normalize(cohort, indices, 1).values)};
    }

    private String[] preprocessedHashes(Cohort cohort, double[] raw) throws Exception {
        float[] query = cohort.preprocessor.transform(raw);
        int[] indices = cohort.context.retrieve(query, contextSize);
        String[] result = new String[cohort.permutations.length];
        for (int member = 0; member < result.length; member++) {
            float[] values = tensorFeatures(cohort.context, indices, query, cohort.permutations[member]);
            result[member] = hashFloats(values);
        }
        return result;
    }

    private static String hashFloats(float[] values) throws Exception {
        ByteBuffer bytes = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : values) bytes.putFloat(value);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes.array());
        StringBuilder text = new StringBuilder(64);
        for (byte value : digest) text.append(String.format("%02x", value));
        return text.toString();
    }

    private Cohort loadCohort(String name, JSONObject metadata) throws Exception {
        String contextFile = metadata.getString("context_file");
        byte[] contextBytes = assets.read(contextFile);
        assets.requireHash(contextFile, contextBytes, metadata.getString("context_sha256"));
        String preprocessorFile = metadata.getString("preprocessor_file");
        Preprocessor preprocessor = new Preprocessor(
                assets.readJson(preprocessorFile, metadata.getString("preprocessor_sha256")));
        JSONArray sourcePermutations = metadata.getJSONArray("feature_permutations");
        int[][] permutations = new int[sourcePermutations.length()][];
        for (int i = 0; i < permutations.length; i++) permutations[i] = Preprocessor.ints(sourcePermutations.getJSONArray(i));
        ContextData data = new ContextData(contextBytes);
        if (data.features != metadata.getInt("transformed_features") || preprocessor.keep.length != data.features)
            throw new IllegalStateException(name + " transformed feature mismatch");
        for (int[] permutation : permutations) if (permutation.length != data.features)
            throw new IllegalStateException(name + " permutation length mismatch");
        JSONObject targetNormalization = metadata.getJSONObject("target_normalization");
        if (!"full_train_before_retrieval".equals(targetNormalization.getString("scope"))
                || !"float32".equals(targetNormalization.getString("dtype"))
                || targetNormalization.getInt("ddof") != 1
                || targetNormalization.getDouble("epsilon") != 1e-6)
            throw new IllegalStateException(name + " target normalization contract mismatch");
        JSONArray channels = targetNormalization.getJSONArray("channels");
        if (channels.length() != data.targets) throw new IllegalStateException(name + " target channel count mismatch");
        float[] targetMeans = new float[data.targets], targetStds = new float[data.targets];
        for (int i = 0; i < channels.length(); i++) {
            JSONObject channel = channels.getJSONObject(i);
            int target = channel.getInt("channel");
            if (target < 0 || target >= data.targets || targetStds[target] != 0.0f)
                throw new IllegalStateException(name + " target channel index mismatch");
            targetMeans[target] = (float)channel.getDouble("mean_float32");
            targetStds[target] = (float)channel.getDouble("std_plus_1e6_float32");
            if (!(targetStds[target] > 0.0f)) throw new IllegalStateException(name + " invalid target std");
        }
        return new Cohort(name, data, preprocessor, permutations, targetMeans, targetStds);
    }

    private float[] predict(Cohort cohort, double[] raw) throws Exception {
        float[] query = cohort.preprocessor.transform(raw);
        int[] indices = cohort.context.retrieve(query, contextSize);
        float[] outputs = new float[2];
        for (int target = 0; target < 2; target++) {
            NormalizedTarget normalized = normalize(cohort, indices, target);
            float[] averaged = new float[bins];
            for (int[] permutation : cohort.permutations) {
                float[] xSource = tensorFeatures(cohort.context, indices, query, permutation);
                float[] logits = infer(xSource, normalized.values);
                for (int i = 0; i < bins; i++) averaged[i] += logits[i];
            }
            if (cohort.permutations.length > 1) {
                float divisor = cohort.permutations.length;
                for (int i = 0; i < bins; i++) averaged[i] /= divisor;
            }
            outputs[target] = distributionMedian(averaged, normalized.mean, normalized.std);
        }
        return outputs;
    }

    private float[] tensorFeatures(ContextData context, int[] indices, float[] query, int[] permutation) {
        float[] result = new float[(contextSize + 1) * 128];
        for (int row = 0; row < contextSize; row++) {
            int sourceOffset = indices[row] * context.features;
            int outputOffset = row * 128;
            for (int feature = 0; feature < permutation.length; feature++)
                result[outputOffset + feature] = context.x[sourceOffset + permutation[feature]];
        }
        int queryOffset = contextSize * 128;
        for (int feature = 0; feature < permutation.length; feature++) result[queryOffset + feature] = query[permutation[feature]];
        return deterministicModelPreprocess(result);
    }

    private float[] deterministicModelPreprocess(float[] source) {
        int rows = contextSize + 1, features = 128;
        double[] values = new double[source.length];
        for (int i = 0; i < source.length; i++) values[i] = source[i];
        deterministicClip(values, rows, features);
        double[] column = new double[contextSize];
        for (int feature = 0; feature < features; feature++) {
            for (int row = 0; row < contextSize; row++) column[row] = values[row * features + feature];
            MeanStd statistics = meanStd(column, contextSize);
            for (int row = 0; row < rows; row++) {
                int index = row * features + feature;
                values[index] = (values[index] - statistics.mean) / (statistics.std + 1e-6);
            }
        }
        deterministicClip(values, rows, features);
        float[] result = new float[values.length];
        for (int i = 0; i < result.length; i++) result[i] = (float)values[i];
        return result;
    }

    private void deterministicClip(double[] values, int rows, int features) {
        double[] context = new double[contextSize];
        double[] inliers = new double[contextSize];
        for (int feature = 0; feature < features; feature++) {
            for (int row = 0; row < contextSize; row++) context[row] = values[row * features + feature];
            MeanStd initial = meanStd(context, contextSize);
            double firstCutoff = 8.0 * initial.std;
            int count = 0;
            for (double value : context) if (Math.abs(value - initial.mean) <= firstCutoff) inliers[count++] = value;
            MeanStd filtered = meanStd(inliers, count);
            double cutoff = 8.0 * filtered.std;
            double low = initial.mean - cutoff, high = initial.mean + cutoff;
            for (int row = 0; row < rows; row++) {
                int index = row * features + feature;
                values[index] = Math.min(high, Math.max(low, values[index]));
            }
        }
    }

    private static MeanStd meanStd(double[] values, int count) {
        if (count == 0) return new MeanStd(0.0, 0.0);
        double total = 0.0;
        for (int i = 0; i < count; i++) total += values[i];
        double mean = total / count;
        if (count == 1) return new MeanStd(mean, 0.0);
        double squared = 0.0;
        for (int i = 0; i < count; i++) { double delta = values[i] - mean; squared += delta * delta; }
        return new MeanStd(mean, Math.sqrt(squared / (count - 1)));
    }

    private float[] infer(float[] xSource, float[] ySource) throws Exception {
        try (OnnxTensor x = OnnxTensor.createTensor(environment, FloatBuffer.wrap(xSource), new long[]{1, contextSize + 1L, 128});
             OnnxTensor y = OnnxTensor.createTensor(environment, FloatBuffer.wrap(ySource), new long[]{1, contextSize})) {
            Map<String, OnnxTensor> inputs = new HashMap<>(); inputs.put("x_src", x); inputs.put("y_src", y);
            try (OrtSession.Result result = session.run(inputs)) {
                FloatBuffer source = ((OnnxTensor)result.get(0)).getFloatBuffer();
                source.position(dropped);
                float[] logits = new float[bins]; source.get(logits);
                return logits;
            }
        }
    }

    private NormalizedTarget normalize(Cohort cohort, int[] indices, int target) {
        float mean = cohort.targetMeans[target], std = cohort.targetStds[target];
        float[] selected = new float[indices.length];
        for (int i = 0; i < indices.length; i++)
            selected[i] = (cohort.context.y[indices[i] * cohort.context.targets + target] - mean) / std;
        return new NormalizedTarget(selected, mean, std);
    }

    private float distributionMedian(float[] logits, float mean, float std) {
        float maximum = -Float.MAX_VALUE;
        for (float value : logits) maximum = Math.max(maximum, value);
        float[] probabilities = new float[logits.length];
        float total = 0.0f;
        for (int i = 0; i < logits.length; i++) {
            probabilities[i] = (float)Math.exp((double)(logits[i] - maximum));
            total += probabilities[i];
        }
        float cumulative = 0.0f, previous = 0.0f;
        int index = probabilities.length - 1;
        for (int i = 0; i < probabilities.length; i++) {
            previous = cumulative;
            cumulative += probabilities[i] / total;
            if (cumulative >= 0.5f) { index = i; break; }
        }
        float probability = probabilities[index] / total;
        float fraction = (0.5f - previous) / probability;
        float step = (binMax - binMin) / bins;
        float left = (binMin + step * index) * std + mean;
        float right = (binMin + step * (index + 1)) * std + mean;
        return left + (right - left) * fraction;
    }

    private static double[] constrain(float[] raw) {
        double onset = Math.min(960.0, Math.max(0.0, raw[0]));
        double wake = Math.min(1440.0, raw[1]);
        wake = Math.max(onset + 30.0, Math.min(onset + 960.0, wake));
        return new double[]{onset, wake};
    }

    public String getManifestSha256() { return manifestSha256; }

    @Override public void close() throws Exception { session.close(); options.close(); }

    private static final class Cohort {
        final String name; final ContextData context; final Preprocessor preprocessor; final int[][] permutations;
        final float[] targetMeans, targetStds;
        Cohort(String name, ContextData context, Preprocessor preprocessor, int[][] permutations,
               float[] targetMeans, float[] targetStds) {
            this.name = name; this.context = context; this.preprocessor = preprocessor; this.permutations = permutations;
            this.targetMeans = targetMeans; this.targetStds = targetStds;
        }
    }
    private static final class NormalizedTarget {
        final float[] values; final float mean, std;
        NormalizedTarget(float[] values, float mean, float std) { this.values = values; this.mean = mean; this.std = std; }
    }
    private static final class MeanStd {
        final double mean, std;
        MeanStd(double mean, double std) { this.mean = mean; this.std = std; }
    }
}
