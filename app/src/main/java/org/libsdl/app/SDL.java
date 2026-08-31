package org.libsdl.app;

import android.app.Activity;

/**
 * Minimal SDL Android context bridge required by the embedded ARMSX2 runtime.
 * ARMSX2 links SDL3 into emucore; Emu Hub does not use SDLActivity itself, but
 * keeping the current Activity here mirrors SDL's Java-side host contract.
 */
public final class SDL {
    private static Activity context;

    private SDL() {}

    public static void setContext(Activity activity) {
        context = activity;
    }

    public static Activity getContext() {
        return context;
    }
}
