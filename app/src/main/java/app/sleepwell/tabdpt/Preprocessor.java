package app.sleepwell.tabdpt;

import org.json.JSONArray;
import org.json.JSONObject;

final class Preprocessor {
    final double[] median, low, high, mean, scale;
    final int[] indicator, keep;

    Preprocessor(JSONObject root) throws Exception {
        JSONObject prep = root.getJSONObject("preprocessing");
        median = doubles(prep.getJSONArray("imputer_median"));
        indicator = ints(prep.getJSONArray("missing_indicator_features"));
        low = doubles(prep.getJSONArray("clip_low")); high = doubles(prep.getJSONArray("clip_high"));
        keep = ints(prep.getJSONArray("variance_keep"));
        mean = doubles(prep.getJSONArray("scale_mean")); scale = doubles(prep.getJSONArray("scale"));
        if (root.getJSONArray("feature_order").length() != median.length) throw new IllegalArgumentException("feature contract mismatch");
    }

    float[] transform(double[] raw) {
        if (raw.length != median.length) throw new IllegalArgumentException("expected " + median.length + " raw features");
        double[] expanded = new double[raw.length + indicator.length];
        for (int i = 0; i < raw.length; i++) {
            if (Double.isInfinite(raw[i])) throw new IllegalArgumentException("infinite raw feature");
            expanded[i] = Double.isNaN(raw[i]) ? median[i] : raw[i];
        }
        for (int i = 0; i < indicator.length; i++) expanded[raw.length + i] = Double.isNaN(raw[indicator[i]]) ? 1.0 : 0.0;
        for (int i = 0; i < expanded.length; i++) expanded[i] = Math.min(high[i], Math.max(low[i], expanded[i]));
        float[] result = new float[keep.length];
        for (int i = 0; i < keep.length; i++) result[i] = (float)((expanded[keep[i]] - mean[i]) / scale[i]);
        return result;
    }

    static double[] doubles(JSONArray source) throws Exception {
        double[] result = new double[source.length()];
        for (int i = 0; i < result.length; i++) result[i] = source.isNull(i) ? Double.NaN : source.getDouble(i);
        return result;
    }
    static int[] ints(JSONArray source) throws Exception {
        int[] result = new int[source.length()];
        for (int i = 0; i < result.length; i++) result[i] = source.getInt(i);
        return result;
    }
}
