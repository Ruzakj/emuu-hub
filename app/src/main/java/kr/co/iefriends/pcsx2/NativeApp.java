package kr.co.iefriends.pcsx2;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.view.Surface;

import java.io.File;

public final class NativeApp {
    private static volatile Context appContext;
    private static volatile boolean loaded;
    private static volatile String loadError;
    private static volatile boolean paused;

    private NativeApp() {}

    public static boolean loadNative(Context context) {
        if (loaded) return true;
        appContext = context.getApplicationContext();
        try {
            long pageSize = 4096L;
            try {
                Class<?> os = Class.forName("android.system.Os");
                Object value = os.getMethod("sysconf", int.class).invoke(null, 30);
                if (value instanceof Long && ((Long) value) > 0) pageSize = (Long) value;
            } catch (Throwable ignored) {}
            String lib = pageSize >= 16384L ? "emucore_16k" : "emucore_4k";
            System.loadLibrary(lib);
            loaded = true;
            loadError = null;
            return true;
        } catch (Throwable t) {
            loadError = t.toString();
            return false;
        }
    }

    public static String getNativeLoadError() { return loadError; }
    public static String nativeLoadError = null;

    public static Context getContext() { return appContext; }

    public static native void initialize(String dataPath, String biosPath, int apiVersion);
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
    public static native void setSetting(String section, String key, String type, String value);
    public static native void commitSettings();
    public static native boolean saveStateToSlot(int slot);
    public static native boolean loadStateFromSlot(int slot);
    public static native boolean isMemcardBusy();

    // Real ARMSX2/PCSX2 runtime telemetry + speed controls. These symbols are
    // implemented by the bundled native core; the Android UI only exposes them.
    public static native float getFPS();
    public static native float getVPS();
    public static native float getEmuSpeedPercent();
    public static native void setNominalSpeed(int percent);
    public static native void speedhackLimitermode(int mode);

    public static void vmSetPaused(boolean value) { paused = value; }
    public static boolean isPaused() { return paused; }
    public static void onPadRumble(int pad, int largeMotor, int smallMotor) {}
    public static void playSound(String path) {}

    public static int openContentUri(String uriString) {
        Context context = getContext();
        if (context == null) return -1;
        ContentResolver resolver = context.getContentResolver();
        try {
            ParcelFileDescriptor pfd = resolver.openFileDescriptor(Uri.parse(uriString), "r");
            return pfd == null ? -1 : pfd.detachFd();
        } catch (Throwable ignored) { return -1; }
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
        } catch (Throwable ignored) { return false; }
    }

    public static int androidApiLevel() { return Build.VERSION.SDK_INT; }
}
