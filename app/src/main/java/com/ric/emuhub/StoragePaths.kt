package com.ric.emuhub

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import java.io.File

/**
 * Central storage layout for every internal emulator.
 *
 * Preferred location (when All Files Access is granted on Android 11+):
 *   /storage/emulated/0/emu-hub/
 *
 * If permission is not available yet we fall back to app-specific internal flash so emulation
 * still works. As soon as permission is granted, new data is written to the visible emu-hub root.
 */
object StoragePaths {
    private const val ROOT_NAME = "emu-hub"

    fun hasSharedRootAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    fun needsSharedRootPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()

    fun permissionIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))

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
        listOf(
            "system", "saves", "states",
            "PS2", "PS2/bios", "PS2/resources", "PS2/memcards", "PS2/savestates"
        ).forEach { File(root, it).mkdirs() }
        migrateLegacyPs2Bios(context, File(root, "PS2/bios"))
        return root
    }

    fun systemDir(context: Context): File = File(ensureLayout(context), "system").apply { mkdirs() }
    fun savesDir(context: Context): File = File(ensureLayout(context), "saves").apply { mkdirs() }
    fun statesDir(context: Context): File = File(ensureLayout(context), "states").apply { mkdirs() }
    fun ps2Root(context: Context): File = File(ensureLayout(context), "PS2").apply { mkdirs() }
    fun ps2BiosDir(context: Context): File = File(ensureLayout(context), "PS2/bios").apply { mkdirs() }

    private fun migrateLegacyPs2Bios(context: Context, target: File) {
        if (target.listFiles()?.any { it.isFile } == true) return
        val legacy = File(context.filesDir, "ps2/bios")
        legacy.listFiles()?.filter { it.isFile }?.forEach { old ->
            runCatching {
                val out = File(target, old.name)
                old.copyTo(out, overwrite = true)
            }
        }
    }
}
