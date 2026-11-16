package me.onj.phonon.android;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

public final class TtsDataActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Intent result = new Intent();
        if (TextToSpeech.Engine.ACTION_GET_SAMPLE_TEXT.equals(getIntent().getAction())) {
            result.putExtra(TextToSpeech.Engine.EXTRA_SAMPLE_TEXT, PreviewSample.forLanguage(getIntent().getStringExtra("language")));
            setResult(TextToSpeech.LANG_AVAILABLE, result);
        } else {
            Set<String> available = new LinkedHashSet<>();
            for (VoiceCatalog.Entry e : VoiceCatalog.entries(this)) {
                Locale locale = e.locale();
                available.add(locale.getISO3Language() + "-" + locale.getISO3Country());
            }
            result.putStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES, new ArrayList<>(available));
            result.putStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES, new ArrayList<>());
            setResult(TextToSpeech.Engine.CHECK_VOICE_DATA_PASS, result);
        }
        finish();
    }
}
