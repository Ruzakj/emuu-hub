package com.ric.emuhub

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import java.io.File

/** Central storage layout for every internal emulator. */
object StoragePaths {
    private const val ROOT_NAME = "emu-hub"
    private const val MIGRATION_PREFS = "storage_paths"
    private const val LEGACY_MIGRATION_KEY = "legacy_migration_v1_complete"

    fun hasSharedRootAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    fun needsSharedRootPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()

    /**
     * Prefer the app-specific all-files settings page, but keep a general settings fallback for
     * Android builds/OEMs that do not expose the package-scoped activity.
     */
    fun permissionIntent(context: Context): Intent {
        val appIntent = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
        return if (appIntent.resolveActivity(context.packageManager) != null) {
            appIntent
        } else {
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        }
    }

    fun root(context: Context): File {
        val base = if (hasSharedRootAccess()) {
            @Suppress("DEPRECATION")
            Environment.getExternalStorageDirectory()
        } else {
            context.getExternalFilesDir(null) ?: context.filesDir
        }
        return File(base, ROOT_NAME).apply { mkdirs() }
    }

    fun ensureLayout(context: Context): File {
        val root = root(context)
        val system = File(root, "system").apply { mkdirs() }
        val saves = File(root, "saves").apply { mkdirs() }
        val states = File(root, "states").apply { mkdirs() }
        val ps2 = File(root, "PS2").apply { mkdirs() }
        File(ps2, "bios").mkdirs()
        File(ps2, "resources").mkdirs()
        File(ps2, "memcards").mkdirs()
        File(ps2, "savestates").mkdirs()

        // Migrate data created by older Emu Hub builds once. Re-scanning the legacy tree on every
        // directory lookup adds avoidable storage I/O, especially for large save/PS2 folders.
        val migrationPrefs = context.getSharedPreferences(MIGRATION_PREFS, Context.MODE_PRIVATE)
        if (!migrationPrefs.getBoolean(LEGACY_MIGRATION_KEY, false)) {
            migrateTree(File(context.filesDir, "system"), system)
            migrateTree(File(context.filesDir, "saves"), saves)
            File(context.filesDir, "saves").listFiles()
                ?.filter { it.isFile && it.name.endsWith(".state", true) }
                ?.forEach { old ->
                    runCatching {
                        if (!File(states, old.name).exists()) {
                            old.copyTo(File(states, old.name), overwrite = false)
                        }
                    }
                }
            migrateTree(File(context.filesDir, "ps2"), ps2)
            migrationPrefs.edit().putBoolean(LEGACY_MIGRATION_KEY, true).apply()
        }
        return root
    }

    fun systemDir(context: Context): File = File(ensureLayout(context), "system").apply { mkdirs() }
    fun savesDir(context: Context): File = File(ensureLayout(context), "saves").apply { mkdirs() }
    fun statesDir(context: Context): File = File(ensureLayout(context), "states").apply { mkdirs() }
    fun ps2Root(context: Context): File = File(ensureLayout(context), "PS2").apply { mkdirs() }
    fun ps2BiosDir(context: Context): File = File(ensureLayout(context), "PS2/bios").apply { mkdirs() }

    private fun migrateTree(source: File, target: File) {
        if (!source.exists() || source.absolutePath == target.absolutePath) return
        source.listFiles()?.forEach { old ->
            val out = File(target, old.name)
            runCatching {
                if (old.isDirectory) {
                    out.mkdirs()
                    migrateTree(old, out)
                } else if (!out.exists() || out.length() == 0L) {
                    old.copyTo(out, overwrite = true)
                }
            }
        }
    }
}
