package me.onj.phonon.android;

import java.util.Locale;

final class PreviewSample {
    private String language;
    private boolean edited;

    PreviewSample(String language) {
        this.language = language;
    }

    void edit() {
        edited = true;
    }

    String follow(String nextLanguage) {
        language = nextLanguage;
        return edited ? null : forLanguage(language);
    }

    String reset() {
        edited = false;
        return forLanguage(language);
    }

    static String forLanguage(String language) {
        String code = language == null ? "" : Locale.forLanguageTag(language.replace('_', '-')).getLanguage();
        return switch (code) {
            case "de", "deu", "ger" -> "Guten Tag! So klingt Phonon auf Deutsch.";
            case "fr", "fra", "fre" -> "Bonjour ! Voici un aper\u00e7u de Phonon en fran\u00e7ais.";
            case "es", "spa" -> "\u00a1Hola! As\u00ed suena Phonon en espa\u00f1ol.";
            case "pt", "por" -> "Ol\u00e1! Este \u00e9 um exemplo da voz do Phonon em portugu\u00eas.";
            default -> "Hello. This is Phonon speaking on Android.";
        };
    }
}
