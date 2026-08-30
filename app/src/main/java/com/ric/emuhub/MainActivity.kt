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
        private val PLAYABLE = setOf("gb", "gbc", "gba", "nes", "sfc", "smc", "bin", "chd", "iso", "cso")
        private val RECOGNIZED = PLAYABLE + "ecm"
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
            text = "Offline emulator\nmGBA: GB / GBC / GBA\nFCEUmm: NES\nSnes9x: SNES\nPCSX-ReARMed: PS1\nPPSSPP: PSP"
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
        if (fromName in RECOGNIZED) return fromName

        return when (contentResolver.getType(uri)?.lowercase()) {
            "application/x-gba-rom", "application/x-gameboy-advance-rom" -> "gba"
            "application/x-gameboy-rom" -> "gb"
            "application/x-gameboy-color-rom" -> "gbc"
            "application/x-nes-rom" -> "nes"
            "application/vnd.nintendo.snes.rom", "application/x-snes-rom", "application/x-super-nintendo-rom" -> "sfc"
            "application/x-cd-image", "application/x-iso9660-image" -> "iso"
            else -> ""
        }
    }

    private fun containsAscii(file: File, token: String, maxBytes: Int = 4 * 1024 * 1024): Boolean {
        val needle = token.toByteArray(Charsets.US_ASCII)
        if (needle.isEmpty()) return false
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            var total = 0
            var carry = ByteArray(0)
            while (total < maxBytes) {
                val want = minOf(buffer.size, maxBytes - total)
                val read = input.read(buffer, 0, want)
                if (read <= 0) break
                total += read
                val data = ByteArray(carry.size + read)
                carry.copyInto(data, 0)
                buffer.copyInto(data, carry.size, 0, read)
                outer@ for (i in 0..data.size - needle.size) {
                    for (j in needle.indices) if (data[i + j] != needle[j]) continue@outer
                    return true
                }
                val keep = minOf(needle.size - 1, data.size)
                carry = data.copyOfRange(data.size - keep, data.size)
            }
        }
        return false
    }

    private fun coreIdFor(ext: String, file: File): String = when (ext) {
        "nes" -> "fceumm"
        "sfc", "smc" -> "snes9x"
        "bin", "chd" -> "pcsx"
        "cso" -> "ppsspp"
        "iso" -> if (containsAscii(file, "PSP_GAME")) "ppsspp" else "pcsx"
        else -> "mgba"
    }

    @Deprecated("Framework compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_ROM || resultCode != RESULT_OK) return

        val uri: Uri = data?.data ?: return
        val name = displayName(uri) ?: "ROM"
        val ext = detectExtension(uri)

        if (ext == "ecm") {
            findViewById<TextView>(STATUS_VIEW_ID).text =
                "PS1 ECM terdeteksi: $name\nECM harus di-decode menjadi .bin/.iso sebelum dijalankan.\nDecoder ECM internal belum dibundel."
            return
        }

        if (ext !in PLAYABLE) {
            findViewById<TextView>(STATUS_VIEW_ID).text =
                "Format belum didukung: $name\nGB/GBC/GBA: .gb .gbc .gba\nNES: .nes | SNES: .sfc .smc\nPS1: .bin .chd .iso | PSP: .iso .cso\nJika file masih .zip/.7z, ekstrak dulu."
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

            val coreId = coreIdFor(ext, out)
            val systemLabel = when (coreId) {
                "pcsx" -> "PS1"
                "ppsspp" -> "PSP"
                else -> "game"
            }
            findViewById<TextView>(STATUS_VIEW_ID).text = "Membuka $name ($systemLabel)..."
            startActivity(
                Intent(this, GameActivity::class.java)
                    .putExtra("romPath", out.absolutePath)
                    .putExtra("coreId", coreId)
                    .putExtra("romName", name)
            )
        } catch (e: Exception) {
            findViewById<TextView>(STATUS_VIEW_ID).text = "Gagal membuka ROM: ${e.message}"
        }
    }
}
