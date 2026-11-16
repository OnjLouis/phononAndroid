package me.onj.phonon.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toolbar;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final String GRADIUM_URL = "https://gradium.ai/";
    private static final String SOFTWARE_URL = "https://onj.me/software/";
    private final ExecutorService background = Executors.newSingleThreadExecutor();
    private final Map<String, String> groups = new LinkedHashMap<>();
    private final List<VoiceCatalog.Entry> voices = new ArrayList<>();
    private TextToSpeech tts;
    private boolean connected;
    private boolean ready;
    private Button languageButton, voiceButton, play, stop, updates;
    private Button resetSample;
    private Button pauseLength;
    private TextView status, systemVoice;
    private EditText text;
    private PreviewSample preview;
    private boolean changingSample;
    private SharedPreferences preferences;
    private String group;
    private VoiceCatalog.Entry voice;
    private int sequence;
    private File pendingUpdate;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = getSharedPreferences("phonon", MODE_PRIVATE);
        voices.addAll(VoiceCatalog.entries(this));
        for (VoiceCatalog.Entry entry : voices) {
            groups.putIfAbsent(key(entry), entry.character() ? "English character voices" : entry.locale().getDisplayName());
        }
        group = preferences.getString("language", "en-GB");
        if (!groups.containsKey(group)) group = groups.keySet().iterator().next();
        voice = VoiceCatalog.named(this, preferences.getString("voice", "phonon-en-GB-freya"));
        if (voice == null || !key(voice).equals(group)) voice = inGroup().get(0);

        LinearLayout frame = new LinearLayout(this);
        frame.setOrientation(LinearLayout.VERTICAL);
        Toolbar toolbar = new Toolbar(this);
        toolbar.setTitle("Phonon");
        MenuItem help = toolbar.getMenu().add("Help");
        help.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        help.setOnMenuItemClickListener(item -> { showHelp(); return true; });
        frame.addView(toolbar);
        ScrollView scroll = new ScrollView(this);
        LinearLayout page = paddedPage(scroll);
        frame.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(frame);
        frame.setOnApplyWindowInsetsListener((view, insets) -> {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars());
                frame.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            }
            return insets;
        });
        heading(page, "Phonon " + BuildConfig.VERSION_NAME, 24);
        status = heading(page, "Preparing voices...", 16);
        status.setAccessibilityHeading(false);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        heading(page, "Voice", 20);
        languageButton = button(page, "Language", () -> chooseLanguage());
        voiceButton = button(page, "Voice", () -> chooseVoice());
        button(page, "Use this voice for system speech", this::useForSystem);
        systemVoice = heading(page, "", 16);
        systemVoice.setAccessibilityHeading(false);
        systemVoice.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        refreshNames();
        refreshSystemVoice();
        pauseLength = button(page, "Pause length: " + PauseLength.selected(this).label, this::choosePauseLength);
        heading(page, "Preview", 20);
        text = new EditText(this);
        text.setHint("Test text");
        preview = new PreviewSample(voice.language());
        text.setText(preview.reset());
        text.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) {
                if (!changingSample) {
                    preview.edit();
                    resetSample.setVisibility(View.VISIBLE);
                }
            }
            @Override public void afterTextChanged(Editable value) { }
        });
        text.setMinLines(3);
        text.setMaxLines(8);
        page.addView(text);
        play = button(page, "Test Voice", this::speak);
        resetSample = button(page, "Reset Sample", () -> {
            stop();
            replaceSample(preview.reset());
            resetSample.setVisibility(View.GONE);
        });
        resetSample.setVisibility(View.GONE);
        stop = button(page, "Stop", this::stop);
        play.setEnabled(false);
        stop.setEnabled(false);
        heading(page, "System", 20);
        button(page, "Open text-to-speech settings", this::openSpeechSettings);
        updates = button(page, "Check for updates", this::checkUpdates);
        heading(page, "About", 20);
        button(page, "Uses Gradium Phonon · gradium.ai", () -> openWebsite(GRADIUM_URL));
        button(page, "Andre's software catalogue", () -> openWebsite(SOFTWARE_URL));

        tts = new TextToSpeech(this, result -> runOnUiThread(() -> {
            if (isDestroyed()) return;
            connected = result == TextToSpeech.SUCCESS;
            play.setEnabled(ready && connected);
            if (!connected && ready) status.setText("Preview could not connect to Phonon. Reopen the app to try again.");
        }), getPackageName());
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) { }
            @Override public void onDone(String id) { finishPreview(id, false); }
            @Override public void onError(String id) { finishPreview(id, true); }
        });
        background.execute(() -> {
            try {
                ModelFiles.prepare(getApplicationContext());
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    ready = true;
                    status.setText("Ready: " + voices.size() + " voices in five languages.");
                    play.setEnabled(connected);
                });
            } catch (Exception e) {
                runOnUiThread(() -> status.setText("Could not prepare the voices. Check available storage and reopen Phonon."));
            }
        });
    }

    private LinearLayout paddedPage(ScrollView scroll) {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(getResources().getDisplayMetrics().density * 16);
        page.setPadding(padding, padding, padding, padding);
        scroll.addView(page);
        return page;
    }

    private void showHelp() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout page = paddedPage(scroll);
        helpSection(page, "What's new in " + BuildConfig.VERSION_NAME,
                "Phonon delivers audio more steadily and responds faster when speech is interrupted. "
                        + "The built-in preview follows the selected language.");
        helpSection(page, "Use with TalkBack",
                "Set Phonon as the preferred engine in Android text-to-speech settings. "
                        + "Choose a voice and tap Use this voice for system speech to use it in TalkBack. "
                        + "Previewing other voices does not change your system voice.");
        helpSection(page, "Voices and controls",
                "Phonon has 102 voices in English, French, German, Spanish and Portuguese. "
                        + "Use Android text-to-speech settings for speaking rate and pitch. "
                        + "Changing languages preserves your preview text. Tap Reset Sample for the built-in text. "
                        + "Pause length adjusts quiet gaps in both previews and system speech. Default leaves those gaps unchanged.");
        helpSection(page, "Privacy",
                "Speech runs on your device. Phonon does not send text or audio to a server. "
                        + "Update checks contact GitHub. External links open in your browser.");
        helpSection(page, "Credits and licence",
                "Uses Gradium Phonon. Created by Andre Louis with development assistance from OpenAI Codex. "
                        + "The multilingual model is licensed for non-commercial use. Third-party notices are included.");
        button(page, "Gradium · gradium.ai", () -> openWebsite(GRADIUM_URL));
        button(page, "Phonon source and model information", () -> openWebsite("https://github.com/gradium-ai/xn-ptts"));
        new AlertDialog.Builder(this).setTitle("Phonon Help").setView(scroll)
                .setPositiveButton("Done", null).show();
    }

    private void helpSection(LinearLayout page, String title, String content) {
        heading(page, title, 20);
        TextView paragraph = new TextView(this);
        paragraph.setText(content);
        paragraph.setTextSize(16);
        page.addView(paragraph);
    }

    private void openWebsite(String address) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(address)));
        } catch (android.content.ActivityNotFoundException e) {
            information("No browser is available to open " + address);
        }
    }

    private TextView heading(LinearLayout page, String name, int size) {
        TextView view = new TextView(this);
        view.setText(name);
        view.setTextSize(size);
        view.setAccessibilityHeading(true);
        page.addView(view);
        return view;
    }

    private Button button(LinearLayout page, String name, Runnable action) {
        Button button = new Button(this);
        button.setText(name);
        button.setAllCaps(false);
        button.setOnClickListener(view -> action.run());
        page.addView(button);
        return button;
    }

    private static String key(VoiceCatalog.Entry e) { return e.character() ? "characters" : e.locale().toLanguageTag(); }

    private List<VoiceCatalog.Entry> inGroup() {
        List<VoiceCatalog.Entry> result = new ArrayList<>();
        for (VoiceCatalog.Entry e : voices) if (key(e).equals(group)) result.add(e);
        return result;
    }

    private void chooseLanguage() {
        List<String> tags = new ArrayList<>(groups.keySet());
        new AlertDialog.Builder(this).setTitle("Language")
                .setSingleChoiceItems(groups.values().toArray(new String[0]), tags.indexOf(group), (dialog, item) -> {
                    String tag = tags.get(item);
                    if (!tag.equals(group)) {
                        group = tag;
                        choose(inGroup().get(0));
                    }
                    dialog.dismiss();
                }).setNegativeButton("Cancel", null).show();
    }

    private void choosePauseLength() {
        PauseLength[] choices = PauseLength.values();
        String[] labels = new String[choices.length];
        for (int i = 0; i < labels.length; ++i) labels[i] = choices[i].label;
        new AlertDialog.Builder(this).setTitle("Pause length")
                .setSingleChoiceItems(labels, PauseLength.selected(this).ordinal(), (dialog, item) -> {
                    stop();
                    choices[item].save(this);
                    pauseLength.setText("Pause length: " + choices[item].label);
                    dialog.dismiss();
                }).setNegativeButton("Cancel", null).show();
    }

    private void chooseVoice() {
        List<VoiceCatalog.Entry> list = inGroup();
        String[] labels = new String[list.size()];
        for (int i = 0; i < labels.length; ++i) labels[i] = list.get(i).name();
        new AlertDialog.Builder(this).setTitle("Voice")
                .setSingleChoiceItems(labels, list.indexOf(voice), (dialog, item) -> {
                    choose(list.get(item));
                    dialog.dismiss();
                }).setNegativeButton("Cancel", null).show();
    }

    private void choose(VoiceCatalog.Entry next) {
        stop();
        voice = next;
        String sample = preview.follow(voice.language());
        if (sample != null) replaceSample(sample);
        preferences.edit().putString("language", group).putString("voice", voice.id()).apply();
        refreshNames();
    }

    private void replaceSample(String sample) {
        if (sample.contentEquals(text.getText())) return;
        changingSample = true;
        try {
            text.setText(sample);
        } finally {
            changingSample = false;
        }
    }

    private void refreshNames() {
        languageButton.setText("Language: " + groups.get(group));
        voiceButton.setText("Voice: " + voice.name());
    }

    private void useForSystem() {
        android.speech.tts.Voice currentDefault = connected ? tts.getDefaultVoice() : null;
        Locale phone = Locale.getDefault();
        Locale locale = currentDefault == null
                ? VoiceCatalog.preferred(this, phone.getLanguage(), phone.getCountry()).locale()
                : currentDefault.getLocale();
        VoiceCatalog.useForSystem(this, voice, locale);
        refreshSystemVoice();
    }

    private void refreshSystemVoice() {
        VoiceCatalog.Entry saved = VoiceCatalog.savedSystemVoice(this);
        systemVoice.setText(saved == null ? "System voice: Automatic" :
                "System voice: " + saved.name() + ", " + groups.get(key(saved)));
    }

    private void speak() {
        if (!connected || !ready || text.length() == 0) return;
        String id = Integer.toString(++sequence);
        if (tts.setVoice(VoiceCatalog.systemVoice(voice)) == TextToSpeech.SUCCESS
                && tts.speak(text.getText().toString(), TextToSpeech.QUEUE_FLUSH, null, id) == TextToSpeech.SUCCESS) {
            status.setText("Speaking with " + voice.name() + ".");
            stop.setEnabled(true);
        } else finishPreview(id, true);
    }

    private void finishPreview(String id, boolean error) {
        runOnUiThread(() -> {
            if (isDestroyed() || !Integer.toString(sequence).equals(id)) return;
            stop.setEnabled(false);
            status.setText(error ? "Phonon could not speak this preview." : "Ready.");
        });
    }

    private void stop() {
        sequence++;
        if (tts != null) tts.stop();
        stop.setEnabled(false);
        if (ready) status.setText("Ready.");
    }

    private void openSpeechSettings() {
        try { startActivity(new Intent("com.android.settings.TTS_SETTINGS")); }
        catch (android.content.ActivityNotFoundException e) { startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
    }

    private void checkUpdates() {
        if (BuildConfig.UPDATE_KEY.isEmpty()) {
            information("This test build is not connected to the public update feed yet.");
            return;
        }
        updates.setEnabled(false);
        background.execute(() -> {
            try {
                UpdateClient.Candidate update = UpdateClient.check(BuildConfig.VERSION_NAME);
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    updates.setEnabled(true);
                    if (update == null) information("Phonon is up to date.");
                    else new AlertDialog.Builder(this).setTitle("Phonon " + update.version)
                            .setMessage("An update is available. Download and verify it now?")
                            .setPositiveButton("Download", (dialog, item) -> download(update))
                            .setNegativeButton("Not now", null).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> updateError("Could not check for updates.", e));
            }
        });
    }

    private void download(UpdateClient.Candidate candidate) {
        updates.setEnabled(false);
        status.setText("Downloading update...");
        background.execute(() -> {
            try {
                File apk = UpdateClient.downloadAndVerify(getApplicationContext(), candidate, (count, size) -> { });
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    updates.setEnabled(true);
                    status.setText("Update verified.");
                    if (UpdateClient.canInstallPackages(this)) UpdateClient.openInstaller(this, apk);
                    else new AlertDialog.Builder(this).setTitle("Allow update installation")
                            .setMessage("Android requires permission for Phonon to open its verified installer.")
                            .setPositiveButton("Open settings", (dialog, item) -> {
                                pendingUpdate = apk;
                                startActivity(UpdateClient.unknownSourcesSettingsIntent(this));
                            }).setNegativeButton("Cancel", null).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> updateError("Could not download the update.", e));
            }
        });
    }

    @Override protected void onResume() {
        super.onResume();
        if (pendingUpdate != null && UpdateClient.canInstallPackages(this)) {
            File apk = pendingUpdate;
            pendingUpdate = null;
            UpdateClient.openInstaller(this, apk);
        }
    }

    private void updateError(String message, Exception failure) {
        if (isDestroyed()) return;
        updates.setEnabled(true);
        status.setText(message);
        android.util.Log.w("Phonon", message, failure);
        information(message);
    }

    private void information(String message) {
        new AlertDialog.Builder(this).setTitle("Phonon").setMessage(message).setPositiveButton("OK", null).show();
    }

    @Override protected void onDestroy() {
        if (tts != null) tts.shutdown();
        background.shutdownNow();
        super.onDestroy();
    }
}
