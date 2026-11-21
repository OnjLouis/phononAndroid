package me.onj.phonon.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.UserManager;

final class BootStorage {
    private BootStorage() {}

    static boolean unlocked(Context context) {
        return context.getSystemService(UserManager.class).isUserUnlocked();
    }

    static Context device(Context context) {
        return context.createDeviceProtectedStorageContext();
    }

    static SharedPreferences speechPreferences(Context context) {
        return device(context).getSharedPreferences("speech-boot", Context.MODE_PRIVATE);
    }
}
