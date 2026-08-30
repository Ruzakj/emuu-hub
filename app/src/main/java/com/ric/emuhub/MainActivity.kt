package com.ric.emuhub

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

class MainActivity : Activity() {
    companion object {
        private const val REQUEST_ROM = 1001
        private const val STATUS_VIEW_ID = 1002
        private val SUPPORTED = setOf("gb", "gbc", "gba", "nes")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }
        root.addView(TextView(this).apply {
            text = "EMU HUB"
            textSize = 32f
            gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            id = STATUS_VIEW_ID
            text = "Offline emulator\nmGBA: GB / GBC / GBA\nFCEUmm: NES"
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 32, 0, 32)
        })
        root.addView(Button(this).apply {
            text = "SELECT GAME"
            setOnClickListener { openRomPicker() }
        })
        setContentView(root)
    }

    private fun openRomPicker() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, REQUEST_ROM)
    }

    private fun displayName(uri: Uri): String? {
        if (uri.scheme == "content") {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) return cursor.getString(index)
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun detectExtension(uri: Uri): String {
        val name = displayName(uri).orEmpty()
        val fromName = name.substringAfterLast('.', "").lowercase()
        if (fromName in SUPPORTED) return fromName

        return when (contentResolver.getType(uri)?.lowercase()) {
            "application/x-gba-rom", "application/x-gameboy-advance-rom" -> "gba"
            "application/x-gameboy-rom" -> "gb"
            "application/x-gameboy-color-rom" -> "gbc"
            "application/x-nes-rom", "application/vnd.nintendo.snes.rom" -> "nes"
            else -> ""
        }
    }

    private fun coreIdFor(ext: String): String = when (ext) {
        "nes" -> "fceumm"
        else -> "mgba"
    }

    @Deprecated("Framework compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_ROM || resultCode != RESULT_OK) return

        val uri: Uri = data?.data ?: return
        val name = displayName(uri) ?: "ROM"
        val ext = detectExtension(uri)

        if (ext !in SUPPORTED) {
            findViewById<TextView>(STATUS_VIEW_ID).text =
                "Format belum didukung: $name\nSaat ini: .gb / .gbc / .gba / .nes\nJika file masih .zip/.7z, ekstrak dulu ROM-nya."
            return
        }

        try {
            val romDir = File(cacheDir, "roms").apply { mkdirs() }
            val safeName = name.replace(Regex("[^A-Za-z0-9._ -]"), "_")
            val out = File(romDir, if (safeName.lowercase().endsWith(".$ext")) safeName else "current.$ext")

            contentResolver.openInputStream(uri)?.use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            } ?: throw IllegalStateException("ROM tidak dapat dibaca")

            if (out.length() == 0L) throw IllegalStateException("File ROM kosong")

            findViewById<TextView>(STATUS_VIEW_ID).text = "Membuka $name..."
            startActivity(
                Intent(this, GameActivity::class.java)
                    .putExtra("romPath", out.absolutePath)
                    .putExtra("coreId", coreIdFor(ext))
                    .putExtra("romName", name)
            )
        } catch (e: Exception) {
            findViewById<TextView>(STATUS_VIEW_ID).text = "Gagal membuka ROM: ${e.message}"
        }
    }
}
