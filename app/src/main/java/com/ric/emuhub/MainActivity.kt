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
import java.io.RandomAccessFile

class MainActivity : Activity() {
    companion object {
        private const val REQUEST_ROM = 1001
        private const val STATUS_VIEW_ID = 1002
        private val PLAYABLE = setOf("gb", "gbc", "gba", "nes", "sfc", "smc", "bin", "cue", "chd", "iso", "cso")
        private val RECOGNIZED = PLAYABLE + "ecm"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(48,48,48,48) }
        root.addView(TextView(this).apply { text = "EMU HUB"; textSize = 32f; gravity = Gravity.CENTER })
        root.addView(TextView(this).apply {
            id = STATUS_VIEW_ID
            text = "Offline emulator\nmGBA: GB / GBC / GBA\nFCEUmm: NES\nSnes9x: SNES\nPCSX-ReARMed: PS1\nPPSSPP: PSP"
            textSize = 16f; gravity = Gravity.CENTER; setPadding(0,32,0,32)
        })
        root.addView(Button(this).apply { text = "SELECT GAME"; setOnClickListener { openRomPicker() } })
        setContentView(root)
    }

    private fun openRomPicker() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"; addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, REQUEST_ROM)
    }

    private fun displayName(uri: Uri): String? {
        if (uri.scheme == "content") contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) { val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (i >= 0) return c.getString(i) }
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun detectExtension(uri: Uri): String {
        val ext = displayName(uri).orEmpty().substringAfterLast('.', "").lowercase()
        if (ext in RECOGNIZED) return ext
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

    /* ISO9660 directory records contain PSP_GAME well beyond the first few MB on some dumps.
       Read the Primary Volume Descriptor and root directory instead of guessing from an early byte scan. */
    private fun isPspIso(file: File): Boolean {
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val sector = 2048L
                val pvd = ByteArray(2048)
                raf.seek(16L * sector); raf.readFully(pvd)
                if (String(pvd, 1, 5, Charsets.US_ASCII) != "CD001") return@use false
                val root = 156
                fun le32(o: Int): Long = (pvd[o].toLong() and 255) or ((pvd[o+1].toLong() and 255) shl 8) or ((pvd[o+2].toLong() and 255) shl 16) or ((pvd[o+3].toLong() and 255) shl 24)
                val rootLba = le32(root + 2)
                val rootSize = le32(root + 10).coerceAtMost(4L * 1024 * 1024)
                if (rootLba <= 0 || rootSize <= 0) return@use false
                val data = ByteArray(rootSize.toInt())
                raf.seek(rootLba * sector); raf.readFully(data)
                var pos = 0
                while (pos < data.size) {
                    val len = data[pos].toInt() and 255
                    if (len == 0) { pos = ((pos / 2048) + 1) * 2048; continue }
                    if (pos + len > data.size || len < 34) break
                    val nameLen = data[pos + 32].toInt() and 255
                    if (pos + 33 + nameLen <= data.size) {
                        val entry = String(data, pos + 33, nameLen, Charsets.US_ASCII).substringBefore(';')
                        if (entry.equals("PSP_GAME", true) || entry.equals("UMD_VIDEO", true) || entry.equals("UMD_AUDIO", true)) return@use true
                    }
                    pos += len
                }
                false
            }
        }.getOrDefault(false)
    }

    private fun coreIdFor(ext: String, file: File): String = when (ext) {
        "nes" -> "fceumm"
        "sfc", "smc" -> "snes9x"
        "bin", "cue", "chd" -> "pcsx"
        "cso" -> "ppsspp"
        "iso" -> if (isPspIso(file)) "ppsspp" else "pcsx"
        else -> "mgba"
    }

    @Deprecated("Framework compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_ROM || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val name = displayName(uri) ?: "ROM"
        val ext = detectExtension(uri)
        if (ext == "ecm") {
            findViewById<TextView>(STATUS_VIEW_ID).text = "PS1 ECM terdeteksi: $name\nDecode ECM menjadi BIN/ISO terlebih dahulu."
            return
        }
        if (ext !in PLAYABLE) {
            findViewById<TextView>(STATUS_VIEW_ID).text = "Format belum didukung: $name\nPS1: .bin .cue .chd .iso | PSP: .iso .cso"
            return
        }
        try {
            val romDir = File(cacheDir, "roms").apply { mkdirs() }
            val safeName = name.replace(Regex("[^A-Za-z0-9._ -]"), "_")
            val out = File(romDir, if (safeName.lowercase().endsWith(".$ext")) safeName else "current.$ext")
            contentResolver.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } }
                ?: throw IllegalStateException("ROM tidak dapat dibaca")
            if (out.length() == 0L) throw IllegalStateException("File ROM kosong")
            val coreId = coreIdFor(ext, out)
            val label = when(coreId) { "pcsx" -> "PS1"; "ppsspp" -> "PSP"; else -> "game" }
            findViewById<TextView>(STATUS_VIEW_ID).text = "Membuka $name ($label)..."
            startActivity(Intent(this, GameActivity::class.java).putExtra("romPath", out.absolutePath).putExtra("coreId", coreId).putExtra("romName", name))
        } catch (e: Exception) {
            findViewById<TextView>(STATUS_VIEW_ID).text = "Gagal membuka ROM: ${e.message}"
        }
    }
}
