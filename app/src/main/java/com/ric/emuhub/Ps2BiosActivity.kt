package com.ric.emuhub

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File

/** BIOS setup is deliberately separated from game launch. */
class Ps2BiosActivity : Activity() {
    companion object {
        private const val REQUEST_BIOS = 2301
        const val PREFS = "ps2"
        const val BIOS_PREF = "ps2_bios_name"

        fun biosDir(activity: Activity): File = File(activity.filesDir, "ps2/bios").apply { mkdirs() }
        fun selectedBios(activity: Activity): File? {
            val dir = biosDir(activity)
            val preferred = activity.getSharedPreferences(PREFS, MODE_PRIVATE).getString(BIOS_PREF, null)
            if (!preferred.isNullOrBlank()) {
                File(dir, preferred).takeIf { validBios(it) }?.let { return it }
            }
            return dir.listFiles()?.firstOrNull { validBios(it) }
        }

        fun validBios(file: File): Boolean = file.isFile && file.length() in 2L * 1024L * 1024L..8L * 1024L * 1024L
    }

    private lateinit var state: TextView
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun rounded(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(34), dp(24), dp(30))
            setBackgroundColor(Color.BLACK)
        }
        root.addView(TextView(this).apply {
            text = "PS2 BIOS SETUP"
            textSize = 25f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "ARMSX2 membutuhkan BIOS PS2 milikmu sebelum game dapat dijalankan. BIOS disimpan lokal di Emu Hub."
            textSize = 13f
            setTextColor(0xFF8C8C8C.toInt())
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })

        state = TextView(this).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = rounded(0xFF0A0A0A.toInt(), 18, 0xFF252525.toInt())
        }
        root.addView(state, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(28) })

        val choose = Button(this).apply {
            text = "PILIH / GANTI BIOS"
            isAllCaps = false
            textSize = 14f
            setTextColor(Color.WHITE)
            background = rounded(0xFF151515.toInt(), 16, 0xFF303030.toInt())
            setOnClickListener { chooseBios() }
        }
        root.addView(choose, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply { topMargin = dp(18) })

        val done = Button(this).apply {
            text = "SELESAI"
            isAllCaps = false
            textSize = 14f
            setTextColor(Color.WHITE)
            background = rounded(0xFF0D0D0D.toInt(), 16, 0xFF252525.toInt())
            setOnClickListener { finish() }
        }
        root.addView(done, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(10) })
        setContentView(root)
        refreshState()
    }

    private fun refreshState() {
        val bios = selectedBios(this)
        if (bios != null) {
            state.text = "BIOS READY\n${bios.name}\n${String.format("%.2f", bios.length() / 1024.0 / 1024.0)} MB"
            state.setTextColor(0xFFE8E8E8.toInt())
        } else {
            state.text = "BIOS BELUM DIPILIH\nPilih BIOS PS2 terlebih dahulu."
            state.setTextColor(0xFFBDBDBD.toInt())
        }
    }

    private fun chooseBios() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, REQUEST_BIOS)
    }

    @Deprecated("Framework compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_BIOS || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        try {
            val dir = biosDir(this)
            val originalName = queryName(uri) ?: "ps2_bios.bin"
            val safeName = originalName.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val temp = File(dir, "$safeName.tmp")
            contentResolver.openInputStream(uri)?.use { input -> temp.outputStream().use { input.copyTo(it) } }
                ?: error("BIOS tidak dapat dibaca")
            if (!validBios(temp)) {
                temp.delete()
                error("Ukuran BIOS tidak valid (${temp.length()} byte)")
            }
            dir.listFiles()?.filter { it != temp }?.forEach { it.delete() }
            val out = File(dir, safeName)
            if (out.exists()) out.delete()
            if (!temp.renameTo(out)) {
                temp.copyTo(out, overwrite = true)
                temp.delete()
            }
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(BIOS_PREF, out.name).apply()
            Toast.makeText(this, "BIOS PS2 tersimpan.", Toast.LENGTH_SHORT).show()
            refreshState()
        } catch (t: Throwable) {
            Toast.makeText(this, "BIOS gagal: ${t.message}", Toast.LENGTH_LONG).show()
            refreshState()
        }
    }

    private fun queryName(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0) return c.getString(i)
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }
}
