package me.onj.phonon.android;

import android.content.Context;
import android.system.Os;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;

final class ModelFiles {
    private static File ready;

    static synchronized File prepare(Context context) throws Exception {
        if (ready != null) return ready;
        JSONObject manifest;
        try (InputStream input = context.getAssets().open("model-manifest.json")) {
            manifest = new JSONObject(VoiceCatalog.readText(input));
        }
        String id = manifest.getString("id");
        if (!id.matches("[0-9a-f]{24}")) throw new IOException("Invalid model identifier");
        File root = modelRoot(BootStorage.device(context));
        File model = new File(root, id);
        if (model.isDirectory()) return ready = model;
        try {
            return ready = prepareModel(context, manifest, root, model);
        } catch (Exception failure) {
            if (BootStorage.unlocked(context)) {
                File legacy = new File(modelRoot(context), id);
                if (legacy.isDirectory()) {
                    Log.w("Phonon", "Boot model preparation failed; retaining the previous model", failure);
                    return legacy;
                }
            }
            throw failure;
        }
    }

    private static File modelRoot(Context context) {
        return new File(context.getNoBackupFilesDir(), "models");
    }

    private static File prepareModel(Context context, JSONObject manifest, File root, File model) throws Exception {
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Could not create the model folder");
        File stage = new File(root, model.getName() + ".partial");
        remove(stage);
        if (!stage.mkdir()) throw new IOException("Could not prepare voice data");
        try {
            JSONArray files = manifest.getJSONArray("files");
            byte[] buffer = new byte[128 * 1024];
            for (int index = 0; index < files.length(); ++index) {
                JSONObject item = files.getJSONObject(index);
                String name = item.getString("name");
                if (!name.matches("(?:voices/)?[^/\\\\]+") || name.contains("..")) throw new IOException("Invalid model path");
                File dest = new File(stage, name);
                File parent = dest.getParentFile();
                if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Could not create the voice folder");
                MessageDigest hash = MessageDigest.getInstance("SHA-256");
                long expected = item.getLong("size"), total = 0;
                try (InputStream input = context.getAssets().open("model/" + name);
                     FileOutputStream output = new FileOutputStream(dest)) {
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        total += count;
                        if (total > expected) throw new IOException("Bundled model data is too large");
                        hash.update(buffer, 0, count);
                        output.write(buffer, 0, count);
                    }
                    output.getFD().sync();
                }
                if (total != expected || !hex(hash.digest()).equals(item.getString("sha256"))) {
                    throw new IOException("Bundled model data did not pass verification");
                }
            }
            Os.rename(stage.getPath(), model.getPath());
        } finally {
            remove(stage);
        }
        return model;
    }

    static synchronized void discardSuperseded(Context context, File active) {
        discardModels(active.getParentFile(), active);
        if (BootStorage.unlocked(context)
                && modelRoot(BootStorage.device(context)).equals(active.getParentFile())) {
            discardModels(modelRoot(context), null);
        }
    }

    private static void discardModels(File root, File keep) {
        File[] models = root.listFiles();
        if (models == null) return;
        for (File file : models) {
            if (!file.equals(keep) && file.getName().matches("[0-9a-f]{24}(?:\\.partial)?")) remove(file);
        }
    }

    private static void remove(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) remove(child);
        file.delete();
    }

    private static String hex(byte[] data) {
        StringBuilder result = new StringBuilder(data.length * 2);
        for (byte value : data) {
            result.append(Character.forDigit((value >> 4) & 15, 16)).append(Character.forDigit(value & 15, 16));
        }
        return result.toString();
    }
}
