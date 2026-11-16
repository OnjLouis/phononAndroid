package me.onj.phonon.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.speech.tts.Voice;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class VoiceCatalog {
    private static final String DEFAULT_PREFIX = "phonon-default-";
    private static final String PREFS = "phonon";
    private static final String SYSTEM_VOICE = "systemVoice";
    private static final String SYSTEM_LANGUAGE = "systemVoiceRequestLanguage";

    record Entry(String id, String name, String modelName, Locale locale, boolean character) {
        String language() { return locale.getLanguage(); }
    }
    private static List<Entry> installed;
    private static Set<Locale> installedLocales;

    static synchronized List<Entry> entries(Context context) {
        if (installed != null) return installed;
        try (InputStream stream = context.getAssets().open("voices.json")) {
            JSONArray json = new JSONArray(readText(stream));
            List<Entry> items = new ArrayList<>();
            Set<String> ids = new LinkedHashSet<>();
            for (int i = 0; i < json.length(); ++i) {
                JSONObject voice = json.getJSONObject(i);
                String name = voice.getString("name");
                String modelName = voice.optString("modelName", name);
                String tag = voice.getString("locale");
                boolean character = tag.equals("characters");
                Locale locale = Locale.forLanguageTag(character ? "en-US" : tag);
                String id = "phonon-" + locale.toLanguageTag() + "-" + Normalizer.normalize(name, Normalizer.Form.NFD)
                        .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
                if (!ids.add(id)) throw new IOException("Duplicate voice identifier");
                items.add(new Entry(id, name, modelName, locale, character));
            }
            items.sort(Comparator.comparing((Entry e) -> e.locale().toLanguageTag())
                    .thenComparing(Entry::character).thenComparing(Entry::name, String.CASE_INSENSITIVE_ORDER));
            Set<Locale> locales = new LinkedHashSet<>();
            for (Entry entry : items) locales.add(entry.locale());
            installedLocales = Collections.unmodifiableSet(locales);
            installed = Collections.unmodifiableList(items);
            return installed;
        } catch (Exception e) {
            throw new IllegalStateException("Bundled voice list is invalid", e);
        }
    }

    static List<Entry> matching(Context context, String lang) {
        if (lang == null) return Collections.emptyList();
        List<Entry> result = new ArrayList<>();
        for (Entry e : entries(context)) {
            if (e.language().equalsIgnoreCase(lang) || e.locale().getISO3Language().equalsIgnoreCase(lang)) result.add(e);
        }
        return result;
    }

    static Entry named(Context context, String id) {
        for (Entry e : entries(context)) if (e.id().equals(id)) return e;
        return null;
    }

    static Entry preferred(Context context, String lang, String country) {
        List<Entry> choices = matching(context, lang);
        for (Entry e : choices) if (!e.character() && hasCountry(e, country)) return e;
        for (Entry e : choices) if (!e.character()) return e;
        for (Entry e : entries(context)) if (e.name().equals("Freya")) return e;
        return entries(context).get(0);
    }

    static boolean hasCountry(Entry e, String country) {
        return e.locale().getCountry().equalsIgnoreCase(country) || e.locale().getISO3Country().equalsIgnoreCase(country);
    }

    static Voice systemVoice(Entry e) {
        return new Voice(e.id(), e.locale(), Voice.QUALITY_HIGH, Voice.LATENCY_NORMAL, false, Collections.emptySet());
    }

    static String defaultName(Locale locale) { return DEFAULT_PREFIX + locale.toLanguageTag(); }

    static synchronized Set<Locale> locales(Context context) {
        entries(context);
        return installedLocales;
    }

    static Locale defaultLocale(Context context, String id) {
        if (id == null || !id.startsWith(DEFAULT_PREFIX)) return null;
        Locale locale = Locale.forLanguageTag(id.substring(DEFAULT_PREFIX.length()));
        return defaultName(locale).equals(id) && locales(context).contains(locale) ? locale : null;
    }

    static Voice defaultVoice(Locale locale) {
        return new Voice(defaultName(locale), locale, Voice.QUALITY_HIGH, Voice.LATENCY_NORMAL, false, Collections.emptySet());
    }

    static Entry savedSystemVoice(Context context) {
        return named(context, preferences(context).getString(SYSTEM_VOICE, null));
    }

    static void useForSystem(Context context, Entry voice, Locale currentDefaultLocale) {
        preferences(context).edit().putString(SYSTEM_VOICE, voice.id())
                .putString(SYSTEM_LANGUAGE, currentDefaultLocale.getLanguage()).apply();
    }

    static Entry forRequest(Context context, String id, String lang, String country) {
        Entry explicit = named(context, id);
        if (explicit != null) return explicit;
        Locale alias = defaultLocale(context, id);
        Entry fallback = alias == null ? preferred(context, lang, country)
                : preferred(context, alias.getLanguage(), alias.getCountry());
        Entry selected = savedSystemVoice(context);
        String requested = fallback.language();
        // Android clients cache the default ID. Resolve it at synthesis time, not when they bind.
        if (selected != null && (requested.equals(selected.language())
                || requested.equals(preferences(context).getString(SYSTEM_LANGUAGE, "")))) return selected;
        return fallback;
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static String readText(InputStream stream) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = stream.read(buffer)) != -1) {
            if (result.size() + count > 128 * 1024) throw new IOException("Bundled JSON is too large");
            result.write(buffer, 0, count);
        }
        return result.toString(StandardCharsets.UTF_8.name());
    }
}
