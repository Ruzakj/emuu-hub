package com.ric.emuhub

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.util.Log
import java.io.File

/**
 * Thin bridge from Emu Hub to the embedded Dolphin Android runtime.
 *
 * This deliberately uses reflection so Emu Hub's regular source tree can still be
 * compiled/audited without checking a large Dolphin AAR binary into git. CI stages
 * the pinned Dolphin 2606a AAR before the final APK build.
 */
object DolphinNativeLauncher {
    private const val TAG_HOST = "DOLPHIN_HOST"
    private const val TAG_JNI = "DOLPHIN_JNI"
    private const val TAG_BOOT = "DOLPHIN_BOOT"
    private const val DOLPHIN_APPLICATION = "org.dolphinemu.dolphinemu.DolphinApplication"
    private const val EMULATION_ACTIVITY = "org.dolphinemu.dolphinemu.activities.EmulationActivity"
    private const val EXTRA_SELECTED_GAMES = "SelectedGames"
    private const val EXTRA_RIIVOLUTION = "Riivolution"

    @Volatile
    private var initialized = false

    fun isEmbedded(): Boolean = runCatching {
        Class.forName(EMULATION_ACTIVITY, false, javaClass.classLoader)
        true
    }.getOrDefault(false)

    @Synchronized
    fun initialize(application: Application): Result<Unit> {
        if (initialized) return Result.success(Unit)
        return runCatching {
            Log.i(TAG_HOST, "Initializing embedded Dolphin 2606a runtime")
            val appClass = Class.forName(DOLPHIN_APPLICATION)
            val init = appClass.getMethod("initialize", Application::class.java)
            init.invoke(null, application)
            initialized = true
            Log.i(TAG_JNI, "Embedded Dolphin runtime initialized; libmain/JNI load requested")
        }.onFailure {
            Log.e(TAG_JNI, "Embedded Dolphin initialization failed", it)
        }
    }

    fun launch(activity: Activity, rom: File): Result<Unit> = runCatching {
        require(rom.isFile && rom.canRead()) { "ROM tidak dapat dibaca: ${rom.absolutePath}" }
        initialize(activity.application).getOrThrow()
        val clazz = Class.forName(EMULATION_ACTIVITY)
        Log.i(TAG_BOOT, "Launching Dolphin internal game path=${rom.absolutePath}")
        val intent = Intent(activity, clazz).apply {
            putExtra(EXTRA_SELECTED_GAMES, arrayOf(rom.absolutePath))
            putExtra(EXTRA_RIIVOLUTION, false)
        }
        activity.startActivity(intent)
    }.onFailure {
        Log.e(TAG_BOOT, "Failed to launch Dolphin internal game", it)
    }
}
