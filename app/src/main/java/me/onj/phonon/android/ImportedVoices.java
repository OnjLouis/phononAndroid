package me.onj.phonon.android;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

final class ImportedVoices {
    static final String ID_PREFIX = "phonon-import-";
    private static final Pattern ID = Pattern.compile(ID_PREFIX + "[0-9a-f]{32}");
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final int MAX_HEADER = 16 * 1024;
    private static final int MAX_VOICES = 64;
    private static final int EMBEDDING_WIDTH = 512;
    private static final int MAX_FRAMES = 512;
    private static final String MODEL_EXT = "ba7a5a45@300";
    private static final String VOICE = "voice.safetensors";
    private static final String METADATA = "voice.json";

    record Entry(String id, String name, Locale locale, File file) {}

    static List<Entry> entries(Context context) {
        File root = root(context);
        File[] children = root.listFiles((dir, name) -> ID.matcher(name).matches());
        List<Entry> results = new ArrayList<>();
        if (children != null) for (File folder : children) {
            if (results.size() == MAX_VOICES) break;
            try {
                File file = new File(folder, VOICE);
                File sidecar = new File(folder, METADATA);
                if (!file.isFile() || !sidecar.isFile() || sidecar.length() > 4096) continue;
                JSONObject metadata = new JSONObject(new String(Files.readAllBytes(sidecar.toPath()), StandardCharsets.UTF_8));
                Locale locale = Locale.forLanguageTag(metadata.getString("locale"));
                String name = checkedName(metadata.getString("name"));
                if (!VoiceCatalog.locales(context).contains(locale)) continue;
                results.add(new Entry(folder.getName(), name, locale, file));
            } catch (IOException | JSONException ignored) {
                // A damaged import must never prevent the stock voices from loading.
            }
        }
        return results;
    }

    static Entry add(Context context, byte[] bytes, String name, Locale locale) throws IOException {
        name = checkedName(name);
        if (!VoiceCatalog.locales(context).contains(locale)) throw new IOException("Select a supported spoken language.");
        validate(bytes);
        if (entries(context).size() >= MAX_VOICES) throw new IOException("Phonon can store up to 64 imported voices.");

        File root = root(context);
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Could not create the imported voices folder.");
        String id = ID_PREFIX + UUID.randomUUID().toString().replace("-", "");
        File destination = new File(root, id);
        File stage = new File(root, "." + id);
        if (!stage.mkdir()) throw new IOException("Could not prepare the imported voice.");
        try {
            write(new File(stage, VOICE), bytes);
            JSONObject sidecar = new JSONObject().put("name", name).put("locale", locale.toLanguageTag());
            write(new File(stage, METADATA), sidecar.toString().getBytes(StandardCharsets.UTF_8));
            Files.move(stage.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE);
        } catch (JSONException e) {
            throw new IOException("Could not save the voice details.", e);
        } finally {
            if (stage.exists()) erase(stage);
        }
        return new Entry(id, name, locale, new File(destination, VOICE));
    }

    static void remove(Context context, String id) throws IOException {
        if (!ID.matcher(id).matches()) throw new IOException("Invalid imported voice.");
        File folder = new File(root(context), id);
        if (!folder.isDirectory()) return;
        File retired = new File(root(context), "." + id);
        // Unpublish the voice before deleting bytes a speech request might already be reading.
        Files.move(folder.toPath(), retired.toPath(), StandardCopyOption.ATOMIC_MOVE);
        erase(retired);
    }

    static void cleanInterruptedWrites(Context context) {
        File[] folders = root(context).listFiles((dir, name) -> name.startsWith(".") && ID.matcher(name.substring(1)).matches());
        if (folders != null) for (File folder : folders) erase(folder);
    }

    private static File root(Context context) { return new File(context.getNoBackupFilesDir(), "imported-voices"); }

    private static void write(File file, byte[] bytes) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(bytes);
            out.getFD().sync();
        }
    }

    private static void erase(File folder) {
        new File(folder, METADATA).delete();
        new File(folder, VOICE).delete();
        folder.delete();
    }

    static String checkedName(String name) throws IOException {
        String clean = name.trim();
        if (clean.isEmpty() || clean.codePointCount(0, clean.length()) > 80
                || clean.codePoints().anyMatch(Character::isISOControl)) {
            throw new IOException("Enter a voice name of 1 to 80 characters.");
        }
        return clean;
    }

    static byte[] read(InputStream stream) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = stream.read(buffer)) != -1) {
            if (count > MAX_BYTES - output.size()) throw invalid();
            output.write(buffer, 0, count);
        }
        byte[] bytes = output.toByteArray();
        validate(bytes);
        return bytes;
    }

    static void validate(byte[] bytes) throws IOException {
        if (bytes.length <= Long.BYTES || bytes.length > MAX_BYTES) throw invalid();
        ByteBuffer data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        long size = data.getLong();
        if (size <= 0 || size > MAX_HEADER || size > data.remaining()) throw invalid();
        int start = Long.BYTES + (int) size;
        try {
            JSONObject header = new JSONObject(new String(bytes, Long.BYTES, (int) size, StandardCharsets.UTF_8));
            Object metadata = header.remove("__metadata__");
            if (metadata != null && !(metadata instanceof JSONObject)) throw invalid();
            if (metadata instanceof JSONObject fields) {
                var keys = fields.keys();
                while (keys.hasNext()) if (!(fields.get(keys.next()) instanceof String)) throw invalid();
                if (fields.has("model_ext") && !MODEL_EXT.equals(fields.getString("model_ext"))) throw invalid();
            }
            if (header.length() != 1) throw invalid();
            String key = header.keys().next();
            boolean latent = key.equals("speaker_wavs");
            if (!latent && !key.equals("emb") && !key.equals("audio_prompt")) throw invalid();
            JSONObject tensor = header.getJSONObject(key);
            if (!"F32".equals(tensor.getString("dtype"))) throw invalid();
            JSONArray shape = tensor.getJSONArray("shape");
            int offset;
            if (shape.length() == 2) offset = 0;
            else if (shape.length() == 3 && shape.getLong(0) == 1) offset = 1;
            else throw invalid();
            long frames = shape.getLong(offset + (latent ? 1 : 0));
            if (frames < 1 || frames > MAX_FRAMES
                    || shape.getLong(offset + (latent ? 0 : 1)) != EMBEDDING_WIDTH) throw invalid();
            int expected = (int) frames * EMBEDDING_WIDTH * Float.BYTES;
            JSONArray offsets = tensor.getJSONArray("data_offsets");
            if (offsets.length() != 2 || offsets.getLong(0) != 0 || offsets.getLong(1) != expected
                    || data.limit() - start != expected) throw invalid();
        } catch (JSONException e) {
            throw invalid();
        }
        data.position(start);
        while (data.hasRemaining()) if (!Float.isFinite(data.getFloat())) throw invalid();
    }

    private static IOException invalid() { return new IOException("Choose a compatible Phonon .safetensors voice file."); }
}
