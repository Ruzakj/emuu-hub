package org.libsdl.app;

/**
 * Minimal SDL controller bridge used only to perform the JNI bootstrap ARMSX2's
 * own Android host executes before starting the VM. Controller discovery/input
 * will be expanded after BIOS/first-frame boot is stable.
 */
public final class SDLControllerManager {
    private SDLControllerManager() {}

    public static native void nativeSetupJNI();

    public static void initialize() {
        // First-frame bootstrap only. Full joystick enumeration is intentionally deferred.
    }
}
