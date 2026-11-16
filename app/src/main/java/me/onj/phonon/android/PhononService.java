package me.onj.phonon.android;

import static android.speech.tts.TextToSpeech.ERROR;
import static android.speech.tts.TextToSpeech.LANG_AVAILABLE;
import static android.speech.tts.TextToSpeech.LANG_COUNTRY_AVAILABLE;
import static android.speech.tts.TextToSpeech.LANG_NOT_SUPPORTED;
import static android.speech.tts.TextToSpeech.SUCCESS;

import android.media.AudioFormat;
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
    private volatile boolean destroyed;

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
            ModelFiles.discardSuperseded(path);
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

    @Override protected void onSynthesizeText(SynthesisRequest request, SynthesisCallback callback) {
        long token = generation.get();
        CharSequence text = request.getCharSequenceText();
        VoiceCatalog.Entry selected = VoiceCatalog.forRequest(this, request.getVoiceName(), request.getLanguage(), request.getCountry());
        synchronized (engineLock) {
            if (destroyed || token != generation.get()) return;
            try {
                NativeEngine active = engine();
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
