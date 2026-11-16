package me.onj.phonon.android;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

final class NativeEngine implements AutoCloseable {
    static final int SAMPLE_RATE = 24000;
    static { System.loadLibrary("phonon_android"); }

    interface AudioSink { boolean accept(byte[] pcm, int length); }

    private long handle;

    NativeEngine(String path) throws IOException {
        try { android.system.Os.setenv("RAYON_NUM_THREADS", "2", false); }
        catch (android.system.ErrnoException e) { throw new IOException("Could not configure speech workers", e); }
        handle = open(utf8(path));
        if (handle == 0) throw new IOException("Could not load the speech model.");
    }

    boolean speak(String text, VoiceCatalog.Entry voice, int rate, int pitch, PauseLength pause, AudioSink sink) {
        return render(handle, utf8(text), utf8(voice.language()), utf8(voice.modelName()),
                scale(rate, 3f), scale(pitch, 2f), pause.nativeMode, sink);
    }

    static float scale(int percent, float maximum) {
        return Math.max(.5f, Math.min(maximum, percent / 100f));
    }

    private static byte[] utf8(String text) {
        return (text.replace('\0', ' ') + '\0').getBytes(StandardCharsets.UTF_8);
    }

    @Override public void close() {
        if (handle != 0) dispose(handle);
        handle = 0;
    }

    private static native long open(byte[] path);
    private static native boolean render(long handle, byte[] text, byte[] lang, byte[] voice,
                                         float speed, float pitch, int pause, AudioSink sink);
    private static native void dispose(long handle);
}
