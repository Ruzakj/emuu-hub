package com.ric.emuhub

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.util.Log
import java.io.File

/**
 * Thin bridge from Emu Hub to the embedded Dolphin Android runtime.
 *
 * Reflection keeps the normal Emu Hub source tree buildable without checking the
 * large Dolphin AAR into git. CI stages the pinned 2606a AAR before the final APK build.
 */
object DolphinNativeLauncher {
    private const val TAG_HOST = "DOLPHIN_HOST"
    private const val TAG_JNI = "DOLPHIN_JNI"
    private const val TAG_BOOT = "DOLPHIN_BOOT"
    private const val TAG_CFG = "DOLPHIN_CONFIG"
    private const val DOLPHIN_APPLICATION = "org.dolphinemu.dolphinemu.DolphinApplication"
    private const val EMULATION_ACTIVITY = "org.dolphinemu.dolphinemu.activities.EmulationActivity"
    private const val DIRECTORY_INITIALIZATION = "org.dolphinemu.dolphinemu.utils.DirectoryInitialization"
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

    private fun directoriesReady(): Boolean = runCatching {
        val cls = Class.forName(DIRECTORY_INITIALIZATION)
        cls.getMethod("areDolphinDirectoriesReady").invoke(null) as Boolean
    }.getOrDefault(false)

    private fun waitForDirectories(timeoutMs: Long = 15_000L): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (directoriesReady()) return true
            try {
                Thread.sleep(100)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return directoriesReady()
    }

    /**
     * Launches the official embedded EmulationActivity only after Dolphin's own
     * DirectoryInitialization has had a chance to bind its real User directory.
     * This is what makes Emu Hub manual settings, save states and GC/Wii saves
     * point at the same persistent storage Dolphin itself uses.
     */
    @Suppress("DEPRECATION")
    fun launch(activity: Activity, rom: File, requestCode: Int? = null): Result<Unit> = runCatching {
        require(rom.isFile && rom.canRead()) { "ROM tidak dapat dibaca: ${rom.absolutePath}" }
        initialize(activity.application).getOrThrow()
        val clazz = Class.forName(EMULATION_ACTIVITY)

        Thread({
            val ready = waitForDirectories()
            val report = runCatching {
                DolphinSettingsActivity.applyPersistedConfig(activity.applicationContext, tryNative = ready)
            }.onFailure {
                Log.e(TAG_CFG, "Failed to apply Emu Hub Dolphin settings", it)
            }.getOrNull()

            if (report != null) {
                Log.i(
                    TAG_CFG,
                    "settings native=${report.nativeApplied} user=${report.userDir.absolutePath} " +
                        "nativeUser=${report.nativeUserDir ?: "not-ready"} config=${report.configDir.absolutePath}"
                )
                report.error?.let { Log.w(TAG_CFG, "Native settings apply warning: $it") }
            }

            val intent = Intent(activity, clazz).apply {
                putExtra(EXTRA_SELECTED_GAMES, arrayOf(rom.absolutePath))
                putExtra(EXTRA_RIIVOLUTION, false)
            }
            Log.i(TAG_BOOT, "Launching Dolphin internal game path=${rom.absolutePath} dirsReady=$ready")
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) {
                    Log.w(TAG_BOOT, "Host activity ended before Dolphin launch")
                    return@runOnUiThread
                }
                if (requestCode == null) activity.startActivity(intent)
                else activity.startActivityForResult(intent, requestCode)
            }
        }, "EmuHub-Dolphin-Launch").apply {
            priority = Thread.NORM_PRIORITY
            start()
        }
    }.onFailure {
        Log.e(TAG_BOOT, "Failed to launch Dolphin internal game", it)
    }
}
