package com.ric.emuhub

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Conservative cleanup for disposable runtime/update artifacts only. Never touches ROMs, saves or settings. */
object StorageMaintenance {
    private const val TAG = "StorageMaintenance"
    private const val PREFS = "storage_maintenance"
    private const val LAST_VERSION = "last_version"
    private const val LAST_RUN = "last_run"
    private const val MAX_FAILED_UPDATE_AGE_MS = 24L * 60L * 60L * 1000L
    private const val MIN_RUN_INTERVAL_MS = 60L * 60L * 1000L
    private val running = AtomicBoolean(false)

    fun runAsync(context: Context) {
        if (!running.compareAndSet(false, true)) return
        val app = context.applicationContext
        Thread({
            try {
                runCatching { run(app) }
                    .onFailure { error -> Log.w(TAG, "Background storage maintenance failed", error) }
            } finally {
                running.set(false)
            }
        }, "emuhub-storage-maintenance").start()
    }

    private fun run(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previousVersion = prefs.getInt(LAST_VERSION, -1)
        val currentVersion = BuildConfig.VERSION_CODE
        val now = System.currentTimeMillis()
        val lastRun = prefs.getLong(LAST_RUN, 0L)

        // Cleanup is best-effort maintenance, not startup-critical work. Avoid repeatedly scanning the same
        // directories during rapid activity/process recreation while still running immediately after an update.
        if (previousVersion == currentVersion && now - lastRun in 0 until MIN_RUN_INTERVAL_MS) return

        ArchiveHelper.cleanupStale(context.cacheDir)
        File(context.cacheDir, "ps2roms").deleteRecursively()

        // Engine installation is transactional. Only incomplete/old staging directories are disposable.
        val engineRoot = File(context.filesDir, "engine_packs")
        engineRoot.listFiles()?.forEach { file ->
            if (file.name.startsWith("incoming-") || file.name == "previous") file.deleteRecursively()
        }

        // External app storage can be unavailable while the device is locked, unmounted, or on unusual OEM builds.
        // In that case skip update cleanup instead of accidentally resolving the path relative to a null parent.
        context.getExternalFilesDir(null)?.let { externalFilesDir ->
            val updates = File(externalFilesDir, "updates")
            if (updates.isDirectory) {
                updates.listFiles()?.forEach { file ->
                    // Package replacement makes old installers obsolete; same-version retries keep only recent files.
                    if (previousVersion != currentVersion || now - file.lastModified() > MAX_FAILED_UPDATE_AGE_MS) {
                        file.deleteRecursively()
                    }
                }
            }
        }
        prefs.edit()
            .putInt(LAST_VERSION, currentVersion)
            .putLong(LAST_RUN, now)
            .apply()
    }
}
