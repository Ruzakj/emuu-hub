package kr.co.iefriends.pcsx2;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.system.OsConstants;
import android.view.Surface;

import java.io.File;
import java.lang.ref.WeakReference;

/** ARMSX2 JNI bridge. Native loading is explicit so the host can isolate and diagnose dlopen. */
public final class NativeApp {
    private NativeApp() {}

    private static WeakReference<Context> contextRef;
    private static volatile boolean paused;
    private static volatile boolean loadAttempted;
    public static volatile boolean hasNoNativeBinary = true;
    public static volatile String nativeLoadError = "not loaded";

    private static long getRuntimePageSize() {
        try {
            long pageSize = Os.sysconf(OsConstants._SC_PAGESIZE);
            return pageSize > 0 ? pageSize : 4096;
        } catch (Throwable ignored) {
            return 4096;
        }
    }

    private static String selectNativeLibraryName() {
        return getRuntimePageSize() >= 16384 ? "emucore_16k" : "emucore_4k";
    }

    public static synchronized boolean loadNative(Context context) {
        attachContext(context);
        if (loadAttempted) return !hasNoNativeBinary;
        loadAttempted = true;
        final String libraryName = selectNativeLibraryName();
        try {
            System.loadLibrary(libraryName);
            hasNoNativeBinary = false;
            nativeLoadError = "";
            return true;
        } catch (UnsatisfiedLinkError e) {
            hasNoNativeBinary = true;
            nativeLoadError = "UnsatisfiedLinkError: " + String.valueOf(e.getMessage());
            return false;
        } catch (Throwable t) {
            hasNoNativeBinary = true;
            nativeLoadError = t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage());
            return false;
        }
    }

    public static boolean isNativeReady() { return !hasNoNativeBinary; }
    public static void attachContext(Context context) { contextRef = new WeakReference<>(context.getApplicationContext()); }
    public static Context getContext() { return contextRef == null ? null : contextRef.get(); }

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
    public static native void setSetting(String section, String key, String type, String value);
    public static native void commitSettings();

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
