package com.ric.emuhub

import android.app.DownloadManager
import android.content.Context
import java.io.File

/** Conservative cleanup for disposable runtime/update artifacts only. Never touches ROMs, saves or settings. */
object StorageMaintenance {
    private const val PREFS = "storage_maintenance"
    private const val LAST_VERSION = "last_version"
    private const val MAX_FAILED_UPDATE_AGE_MS = 24L * 60L * 60L * 1000L

    fun runAsync(context: Context) {
        val app = context.applicationContext
        Thread({ runCatching { run(app) } }, "emuhub-storage-maintenance").start()
    }

    private fun run(context: Context) {
        ArchiveHelper.cleanupStale(context.cacheDir)
        File(context.cacheDir, "ps2roms").deleteRecursively()

        // Engine installation is transactional. Only incomplete/old staging directories are disposable.
        val engineRoot = File(context.filesDir, "engine_packs")
        engineRoot.listFiles()?.forEach { file ->
            if (file.name.startsWith("incoming-") || file.name == "previous") file.deleteRecursively()
        }

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previousVersion = prefs.getInt(LAST_VERSION, -1)
        val currentVersion = BuildConfig.VERSION_CODE
        val updates = File(context.getExternalFilesDir(null), "updates")
        if (updates.isDirectory) {
            val now = System.currentTimeMillis()
            updates.listFiles()?.forEach { file ->
                // Package replacement makes old installers obsolete; same-version retries keep only recent files.
                if (previousVersion != currentVersion || now - file.lastModified() > MAX_FAILED_UPDATE_AGE_MS) {
                    file.deleteRecursively()
                }
            }
        }
        prefs.edit().putInt(LAST_VERSION, currentVersion).apply()
    }
}
