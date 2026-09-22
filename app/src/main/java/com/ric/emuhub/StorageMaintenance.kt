package com.ric.emuhub

import android.content.Context
import android.os.Process
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
        val worker = Thread({
            try {
                // Filesystem cleanup is not latency-sensitive. Prefer background priority, but do not skip
                // maintenance if an unusual device/runtime rejects the priority change.
                runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
                    .onFailure { error -> Log.w(TAG, "Unable to lower storage maintenance priority", error) }
                runCatching { run(app) }
                    .onFailure { error -> Log.w(TAG, "Background storage maintenance failed", error) }
            } finally {
                running.set(false)
            }
        }, "emuhub-storage-maintenance")

        // Thread creation/start can fail under severe resource pressure. Release the guard so a later invocation
        // can retry instead of leaving storage maintenance permanently disabled for the rest of the process.
        try {
            worker.start()
        } catch (error: Throwable) {
            running.set(false)
            Log.w(TAG, "Unable to start storage maintenance worker", error)
        }
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
        deleteRecursivelyBestEffort(File(context.cacheDir, "ps2roms"), "PS2 ROM cache")

        // Engine installation is transactional. Only incomplete/old staging directories are disposable.
        val engineRoot = File(context.filesDir, "engine_packs")
        engineRoot.listFiles()?.forEach { file ->
            if (file.name.startsWith("incoming-") || file.name == "previous") {
                deleteRecursivelyBestEffort(file, "engine staging ${file.name}")
            }
        }

        // External app storage can be unavailable while the device is locked, unmounted, or on unusual OEM builds.
        // In that case skip update cleanup instead of accidentally resolving the path relative to a null parent.
        context.getExternalFilesDir(null)?.let { externalFilesDir ->
            val updates = File(externalFilesDir, "updates")
            if (updates.isDirectory) {
                updates.listFiles()?.forEach { file ->
                    // Package replacement makes old installers obsolete; same-version retries keep only recent files.
                    if (previousVersion != currentVersion || now - file.lastModified() > MAX_FAILED_UPDATE_AGE_MS) {
                        deleteRecursivelyBestEffort(file, "update artifact ${file.name}")
                    }
                }
            }
        }

        // This runs on our maintenance worker, so synchronously persist completion before the worker exits.
        // That keeps rapid process restarts from losing the throttle marker and repeating the same filesystem scan.
        if (!prefs.edit()
                .putInt(LAST_VERSION, currentVersion)
                .putLong(LAST_RUN, now)
                .commit()
        ) {
            Log.w(TAG, "Unable to persist storage maintenance completion")
        }
    }

    private fun deleteRecursivelyBestEffort(file: File, label: String) {
        if (file.exists() && !file.deleteRecursively()) {
            Log.w(TAG, "Unable to delete $label at ${file.absolutePath}")
        }
    }
}
