package me.onj.phonon.android;

import static android.speech.tts.TextToSpeech.ERROR;
import static android.speech.tts.TextToSpeech.LANG_AVAILABLE;
import static android.speech.tts.TextToSpeech.LANG_COUNTRY_AVAILABLE;
import static android.speech.tts.TextToSpeech.LANG_NOT_SUPPORTED;
import static android.speech.tts.TextToSpeech.SUCCESS;

import android.media.AudioFormat;
import android.content.Context;
import android.speech.tts.SynthesisCallback;
import android.speech.tts.SynthesisRequest;
import android.speech.tts.TextToSpeechService;
import android.speech.tts.Voice;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

public final class PhononService extends TextToSpeechService {
    private final AtomicLong generation = new AtomicLong();
    private final Object engineLock = new Object();
    private NativeEngine engine;
    private File modelPath;
    private boolean unlockedPrepared;
    private volatile boolean destroyed;
    private static final String DRAFT_FILE = "draft-voice.safetensors";

    @Override public void onCreate() {
        super.onCreate();
        new Thread(() -> {
            synchronized (engineLock) {
                if (destroyed) return;
                try { engine(); }
                catch (Exception | LinkageError error) { Log.w("Phonon", "Model preload failed", error); }
            }
        }, "Phonon preload").start();
    }

    private NativeEngine engine() throws Exception {
        if (engine == null) {
            File path = ModelFiles.prepare(this);
            engine = new NativeEngine(path.getPath());
            modelPath = path;
            ModelFiles.discardSuperseded(this, path);
        }
        if (!unlockedPrepared && BootStorage.unlocked(this)) {
            new File(getCacheDir(), DRAFT_FILE).delete();
            VoiceCatalog.syncBootSelection(this);
            PauseLength.syncBootPreference(this);
            ModelFiles.discardSuperseded(this, modelPath);
            unlockedPrepared = true;
        }
        return engine;
    }

    @Override protected String[] onGetLanguage() {
        Locale l = Locale.getDefault();
        Locale available = VoiceCatalog.preferred(this, l.getLanguage(), l.getCountry()).locale();
        return new String[]{available.getISO3Language(), available.getISO3Country(), ""};
    }

    @Override protected int onIsLanguageAvailable(String lang, String country, String variant) {
        List<VoiceCatalog.Entry> voices = VoiceCatalog.matching(this, lang);
        if (voices.isEmpty()) return LANG_NOT_SUPPORTED;
        for (VoiceCatalog.Entry voice : voices) if (VoiceCatalog.hasCountry(voice, country)) return LANG_COUNTRY_AVAILABLE;
        return LANG_AVAILABLE;
    }

    @Override protected int onLoadLanguage(String lang, String country, String variant) {
        return onIsLanguageAvailable(lang, country, variant);
    }

    @Override public List<Voice> onGetVoices() {
        List<Voice> voices = new ArrayList<>();
        for (VoiceCatalog.Entry entry : VoiceCatalog.entries(this)) voices.add(VoiceCatalog.systemVoice(entry));
        for (Locale locale : VoiceCatalog.locales(this)) voices.add(VoiceCatalog.defaultVoice(locale));
        return voices;
    }

    @Override public int onIsValidVoiceName(String name) {
        return VoiceCatalog.named(this, name) != null || VoiceCatalog.defaultLocale(this, name) != null ? SUCCESS : ERROR;
    }

    @Override public int onLoadVoice(String name) { return onIsValidVoiceName(name); }

    @Override public String onGetDefaultVoiceNameFor(String lang, String country, String variant) {
        return VoiceCatalog.defaultName(VoiceCatalog.preferred(this, lang, country).locale());
    }

    @Override protected void onStop() { generation.incrementAndGet(); }

    private static volatile Draft draft;
    record Draft(String token, byte[] data, Locale locale) {}

    static void setDraft(Draft value) { draft = value; }
    static String draftKey(Context context) { return context.getPackageName() + ":draftVoice"; }

    private static void rejectDraft(SynthesisCallback callback) {
        // Older Android file-synthesis callbacks only report an error after start/done.
        if (callback.start(NativeEngine.SAMPLE_RATE, AudioFormat.ENCODING_PCM_16BIT, 1) == SUCCESS) {
            callback.error();
            callback.done();
        }
    }

    @Override protected void onSynthesizeText(SynthesisRequest request, SynthesisCallback callback) {
        long token = generation.get();
        CharSequence text = request.getCharSequenceText();
        synchronized (engineLock) {
            if (destroyed || token != generation.get()) return;
            File draftFile = null;
            try {
                NativeEngine active = engine();
                String draftToken = BootStorage.unlocked(this) && request.getCallerUid() == android.os.Process.myUid()
                        ? request.getParams().getString(draftKey(this)) : null;
                VoiceCatalog.Entry voice;
                if (draftToken == null) {
                    voice = VoiceCatalog.forRequest(this, request.getVoiceName(), request.getLanguage(), request.getCountry());
                } else {
                    Draft pending = draft;
                    if (pending == null || !pending.token().equals(draftToken)) { rejectDraft(callback); return; }
                    draftFile = new File(getCacheDir(), DRAFT_FILE);
                    java.nio.file.Files.write(draftFile.toPath(), pending.data());
                    voice = new VoiceCatalog.Entry(pending.token(), "", "", pending.locale(), false, draftFile);
                }
                VoiceCatalog.Entry selected = voice;
                if (!active.prepareVoice(voice)) {
                    if (draftToken != null) { rejectDraft(callback); return; }
                    selected = VoiceCatalog.preferred(this, voice.language(), voice.locale().getCountry());
                }
                if (token != generation.get()) return;
                if (callback.start(NativeEngine.SAMPLE_RATE, AudioFormat.ENCODING_PCM_16BIT, 1) != SUCCESS) return;
                boolean ok = text == null || text.toString().trim().isEmpty() || active.speak(text.toString(), selected,
                        request.getSpeechRate(), request.getPitch(), PauseLength.selected(this), (pcm, length) -> {
                            int max = callback.getMaxBufferSize() & ~1;
                            if (max == 0 || destroyed || token != generation.get()) return false;
                            for (int offset = 0; offset < length; offset += max) {
                                if (token != generation.get()
                                        || callback.audioAvailable(pcm, offset, Math.min(max, length - offset)) != SUCCESS) return false;
                            }
                            return true;
                        });
                if (token != generation.get() || destroyed) return;
                if (ok) callback.done(); else callback.error();
            } catch (Exception | LinkageError error) {
                Log.w("Phonon", "Speech request failed", error);
                if (token == generation.get() && !destroyed) callback.error();
            } finally {
                if (draftFile != null && !draftFile.delete()) Log.w("Phonon", "Could not clear a preview file");
            }
        }
    }

    @Override public void onDestroy() {
        destroyed = true;
        onStop();
        new Thread(() -> {
            synchronized (engineLock) {
                if (engine != null) engine.close();
                engine = null;
            }
        }, "Phonon shutdown").start();
        super.onDestroy();
    }
}
