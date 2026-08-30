package com.ric.emuhub

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

class MainActivity : Activity() {
    companion object {
        private const val REQUEST_ROM = 1001
        private const val STATUS_VIEW_ID = 1002
        private val SUPPORTED = setOf("gb", "gbc", "gba")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }
        root.addView(TextView(this).apply { text = "EMU HUB"; textSize = 32f; gravity = Gravity.CENTER })
        root.addView(TextView(this).apply {
            id = STATUS_VIEW_ID
            text = "Offline emulator\nmGBA core: GB / GBC / GBA"
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 32, 0, 32)
        })
        root.addView(Button(this).apply { text = "SELECT GAME"; setOnClickListener { openRomPicker() } })
        setContentView(root)
    }

    private fun openRomPicker() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, REQUEST_ROM)
    }

    @Deprecated("Framework compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_ROM || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "game.gba"
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext !in SUPPORTED) {
            findViewById<TextView>(STATUS_VIEW_ID).text = "Format belum didukung: .$ext\nGunakan .gb / .gbc / .gba"
            return
        }
        try {
            val romDir = File(cacheDir, "roms").apply { mkdirs() }
            val out = File(romDir, "current.$ext")
            contentResolver.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } }
                ?: throw IllegalStateException("ROM tidak dapat dibaca")
            startActivity(Intent(this, GameActivity::class.java).putExtra("romPath", out.absolutePath))
        } catch (e: Exception) {
            findViewById<TextView>(STATUS_VIEW_ID).text = "Gagal membuka ROM: ${e.message}"
        }
    }
}
