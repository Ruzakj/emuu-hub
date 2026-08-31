package kr.co.iefriends.pcsx2;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.view.Surface;

import java.io.File;
import java.lang.ref.WeakReference;

/** Minimal ARMSX2 Android JNI bridge used by Emu Hub's internal PS2 activity. */
public final class NativeApp {
    private NativeApp() {}

    private static WeakReference<Context> contextRef;
    private static volatile boolean paused;

    static {
        System.loadLibrary("emucore_4k");
    }

    public static void attachContext(Context context) {
        contextRef = new WeakReference<>(context.getApplicationContext());
    }

    public static Context getContext() {
        return contextRef == null ? null : contextRef.get();
    }

    public static native void initialize(String path, String biosFolder, int apiVer);
    public static native void onNativeSurfaceCreated();
    public static native void onNativeSurfaceChanged(Surface surface, int w, int h);
    public static native void onNativeSurfaceDestroyed();
    public static native void setDisplayRefreshRate(float hz);
    public static native boolean runVMThread(String path);
    public static native void shutdown();
    public static native void pause();
    public static native void resume();
    public static native boolean hasActiveVM();
    public static native void setPadButton(int index, int range, boolean pressed);
    public static native void resetKeyStatus();
    public static native void setAspectRatio(int type);
    public static native void renderVulkan();
    public static native void renderUpscalemultiplier(float value);
    public static native void setAffinityMode(int mode);
    public static native void setAdpfEnabled(boolean enabled);
    public static native void setAudioVolume(int volume);
    public static native void setAudioMuted(boolean muted);
    public static native void flushShaderCache();

    // Native callbacks looked up during initialize(). Keep exact names/signatures.
    public static void vmSetPaused(boolean value) {
        paused = value;
    }

    public static boolean isPaused() {
        return paused;
    }

    public static void onPadRumble(int pad, int largeMotor, int smallMotor) {
        // Intentionally no-op for the first Emu Hub integration pass.
    }

    public static void playSound(String path) {
        // RetroAchievements/UI sounds are not part of the offline PS2 runtime.
    }

    public static int openContentUri(String uriString) {
        Context context = getContext();
        if (context == null) return -1;
        ContentResolver resolver = context.getContentResolver();
        try {
            ParcelFileDescriptor pfd = resolver.openFileDescriptor(Uri.parse(uriString), "r");
            return pfd == null ? -1 : pfd.detachFd();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    public static boolean createDirectoryPath(String path) {
        if (path == null || path.isEmpty()) return false;
        File file = new File(path);
        return file.isDirectory() || file.mkdirs();
    }

    public static boolean createFilePath(String path) {
        if (path == null || path.isEmpty()) return false;
        try {
            File file = new File(path);
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return false;
            return file.exists() || file.createNewFile();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static int androidApiLevel() {
        return Build.VERSION.SDK_INT;
    }
}
