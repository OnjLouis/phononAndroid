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
        int saved = context.getSharedPreferences("phonon", Context.MODE_PRIVATE).getInt(KEY, 0);
        return switch (saved) {
            case 1 -> SHORTER;
            case 2 -> LONGER;
            default -> DEFAULT;
        };
    }

    void save(Context context) {
        context.getSharedPreferences("phonon", Context.MODE_PRIVATE).edit().putInt(KEY, nativeMode).apply();
    }
}
