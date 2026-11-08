package app.sleepwell.tabdpt;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

final class BundleAssets {
    static final String ROOT = "tabdpt/";
    final Context app;
    final JSONObject manifest;
    final String manifestSha256;
    final Map<String, String> hashes = new HashMap<>();

    BundleAssets(Context context) throws Exception {
        app = context.getApplicationContext();
        byte[] manifestBytes = read("manifest.json");
        manifestSha256 = hex(MessageDigest.getInstance("SHA-256").digest(manifestBytes));
        manifest = new JSONObject(new String(manifestBytes, StandardCharsets.UTF_8));
        JSONArray files = manifest.getJSONArray("files");
        for (int i = 0; i < files.length(); i++) {
            JSONObject file = files.getJSONObject(i);
            hashes.put(file.getString("name"), file.getString("sha256"));
        }
    }

    byte[] read(String name) throws Exception {
        try (InputStream input = app.getAssets().open(ROOT + name);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }

    JSONObject readJson(String name, String expectedHash) throws Exception {
        byte[] bytes = read(name);
        requireHash(name, bytes, expectedHash);
        return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
    }

    File materialize(String name) throws Exception {
        File directory = new File(app.getNoBackupFilesDir(), "tabdpt-bundle");
        if (!directory.exists() && !directory.mkdirs()) throw new IllegalStateException("cannot create model directory");
        File output = new File(directory, name);
        String expected = hashes.get(name);
        if (expected == null) throw new IllegalStateException("unregistered bundle file " + name);
        if (output.isFile() && expected.equals(sha256(output))) return output;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        File temporary = new File(directory, name + ".tmp");
        try (InputStream input = app.getAssets().open(ROOT + name);
             FileOutputStream stream = new FileOutputStream(temporary)) {
            byte[] buffer = new byte[1024 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                stream.write(buffer, 0, count);
                digest.update(buffer, 0, count);
            }
            stream.getFD().sync();
        }
        String actual = hex(digest.digest());
        if (!expected.equals(actual)) throw new IllegalStateException(name + " asset hash mismatch");
        if (output.exists() && !output.delete()) throw new IllegalStateException("cannot replace " + name);
        if (!temporary.renameTo(output)) throw new IllegalStateException("cannot finalize " + name);
        return output;
    }

    void requireHash(String name, byte[] bytes, String expected) throws Exception {
        String actual = hex(MessageDigest.getInstance("SHA-256").digest(bytes));
        if (!expected.equals(actual)) throw new IllegalStateException(name + " hash mismatch");
    }
    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[1024 * 1024]; int count;
            while ((count = input.read(buffer)) >= 0) digest.update(buffer, 0, count);
        }
        return hex(digest.digest());
    }
    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) out.append(String.format("%02x", value));
        return out.toString();
    }
}
