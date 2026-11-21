package me.onj.phonon.android;

import android.content.Context;

enum PauseLength {
    DEFAULT(0, "Default"),
    SHORTER(1, "Shorter"),
    LONGER(2, "Longer");

    private static final String KEY = "pauseLength";
    final int nativeMode;
    final String label;

    PauseLength(int nativeMode, String label) {
        this.nativeMode = nativeMode;
        this.label = label;
    }

    static PauseLength selected(Context context) {
        int saved = (BootStorage.unlocked(context)
                ? context.getSharedPreferences("phonon", Context.MODE_PRIVATE)
                : BootStorage.speechPreferences(context)).getInt(KEY, 0);
        return switch (saved) {
            case 1 -> SHORTER;
            case 2 -> LONGER;
            default -> DEFAULT;
        };
    }

    void save(Context context) {
        context.getSharedPreferences("phonon", Context.MODE_PRIVATE).edit().putInt(KEY, nativeMode).apply();
        syncBootPreference(context);
    }

    static synchronized void syncBootPreference(Context context) {
        if (BootStorage.unlocked(context)) {
            BootStorage.speechPreferences(context).edit().putInt(KEY, selected(context).nativeMode).apply();
        }
    }
}
