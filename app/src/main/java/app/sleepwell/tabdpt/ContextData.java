package app.sleepwell.tabdpt;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.PriorityQueue;

final class ContextData {
    final int rows, features, targets;
    final float[] x, y;

    ContextData(byte[] artifact) {
        ByteBuffer input = ByteBuffer.wrap(artifact).order(ByteOrder.LITTLE_ENDIAN);
        byte[] magic = new byte[8]; input.get(magic);
        if (!"SWCTX01\0".equals(new String(magic, StandardCharsets.US_ASCII))) throw new IllegalArgumentException("bad context magic");
        rows = input.getInt(); features = input.getInt(); targets = input.getInt();
        if (rows <= 0 || features <= 0 || targets != 2) throw new IllegalArgumentException("bad context dimensions");
        x = new float[rows * features]; y = new float[rows * targets];
        for (int i = 0; i < x.length; i++) x[i] = input.getFloat();
        for (int i = 0; i < y.length; i++) y[i] = input.getFloat();
        if (input.hasRemaining()) throw new IllegalArgumentException("trailing context bytes");
    }

    int[] retrieve(float[] query, int k) {
        if (query.length != features || k < 1 || k > rows) throw new IllegalArgumentException("retrieval dimensions");
        double[] distances = new double[rows];
        Comparator<Integer> worstFirst = (a, b) -> {
            int compared = Double.compare(distances[b], distances[a]);
            return compared != 0 ? compared : Integer.compare(b, a);
        };
        PriorityQueue<Integer> selected = new PriorityQueue<>(k, worstFirst);
        for (int row = 0; row < rows; row++) {
            double distance = 0.0;
            int offset = row * features;
            for (int feature = 0; feature < features; feature++) {
                double delta = (double)x[offset + feature] - (double)query[feature];
                distance += delta * delta;
            }
            distances[row] = distance;
            if (selected.size() < k) selected.add(row);
            else {
                int worst = selected.peek();
                if (distance < distances[worst] || (distance == distances[worst] && row < worst)) {
                    selected.poll(); selected.add(row);
                }
            }
        }
        Integer[] boxed = selected.toArray(new Integer[0]);
        Arrays.sort(boxed, (a, b) -> {
            int compared = Double.compare(distances[a], distances[b]);
            return compared != 0 ? compared : Integer.compare(a, b);
        });
        int[] result = new int[k];
        for (int i = 0; i < k; i++) result[i] = boxed[i];
        return result;
    }
}
