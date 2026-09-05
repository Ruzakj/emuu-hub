package com.ric.emuhub

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

class DolphinSettingsActivity : Activity() {
    companion object {
        private const val PREFS = "dolphin_engine_settings"
        private const val KEY_BACKEND = "backend"
        private const val KEY_RESOLUTION = "resolution"
        private const val KEY_DUAL_CORE = "dual_core"
        private const val KEY_BACKEND_MT = "backend_mt"
        private const val KEY_SHADER_MODE = "shader_mode"
        private const val KEY_VSYNC = "vsync"
        private const val KEY_SPEED = "speed"
        private const val KEY_MMU = "mmu"
    }

    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private lateinit var root: LinearLayout

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }
    private fun tv(text: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        includeFontPadding = false
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ensureDefaults()
        render()
        writeConfig()
    }

    private fun ensureDefaults() {
        if (!prefs.contains(KEY_BACKEND)) {
            prefs.edit()
                .putString(KEY_BACKEND, "Vulkan")
                .putString(KEY_RESOLUTION, "1")
                .putBoolean(KEY_DUAL_CORE, true)
                .putBoolean(KEY_BACKEND_MT, true)
                .putString(KEY_SHADER_MODE, "hybrid")
                .putBoolean(KEY_VSYNC, false)
                .putString(KEY_SPEED, "100")
                .putBoolean(KEY_MMU, false)
                .apply()
        }
    }

    private fun render() {
        window.statusBarColor = 0xFF030406.toInt()
        window.navigationBarColor = 0xFF030406.toInt()
        val scroll = ScrollView(this).apply { setBackgroundColor(0xFF030406.toInt()) }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(32))
        }
        root.addView(tv("GAMECUBE / WII", 23f, 0xFFFFFFFF.toInt(), true))
        root.addView(tv("Official Dolphin engine profile", 10f, 0xFF8793A4.toInt()), lp(top = 4))
        root.addView(tv("Default profile is tuned for Android ARM64: Vulkan, Dual Core, backend multithreading, 1× native resolution and conservative compatibility settings.", 11f, 0xFFAAB4C2.toInt()), lp(top = 12))

        addChoice("Video Backend", KEY_BACKEND, arrayOf("Vulkan", "OpenGL"), arrayOf("Vulkan • Recommended", "OpenGL • Compatibility fallback"))
        addChoice("Internal Resolution", KEY_RESOLUTION, arrayOf("1", "2"), arrayOf("1× Native • Performance", "2× Native • Sharper"))
        addChoice("Shader Compilation", KEY_SHADER_MODE, arrayOf("hybrid", "skip"), arrayOf("Hybrid Ubershaders • Stable", "Skip Drawing • Faster, more glitches"))
        addToggle("Dual Core", KEY_DUAL_CORE, "Official Dolphin Android default; normally improves speed.")
        addToggle("Vulkan Backend Multithreading", KEY_BACKEND_MT, "Recommended when Vulkan is selected.")
        addToggle("V-Sync", KEY_VSYNC, "Usually unnecessary on Android; leave off unless needed.")
        addToggle("MMU", KEY_MMU, "Compatibility option for specific games; slower. Keep off globally.")
        addChoice("Emulation Speed", KEY_SPEED, arrayOf("100", "90", "80"), arrayOf("100% • Normal", "90%", "80%"))

        root.addView(tv("Per-game overrides can be added later without changing these global defaults.", 10f, 0xFF6F7A89.toInt()), lp(top = 20))
        scroll.addView(root)
        setContentView(scroll)
    }

    private fun addChoice(title: String, key: String, values: Array<String>, labels: Array<String>) {
        val row = tile(title, currentLabel(key, values, labels))
        row.setOnClickListener {
            val current = prefs.getString(key, values[0]) ?: values[0]
            val checked = values.indexOf(current).coerceAtLeast(0)
            AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(labels, checked) { d, which ->
                    prefs.edit().putString(key, values[which]).apply()
                    writeConfig()
                    d.dismiss()
                    render()
                }
                .setNegativeButton("Batal", null)
                .show()
        }
        root.addView(row, lp(top = 10))
    }

    private fun addToggle(title: String, key: String, description: String) {
        val enabled = prefs.getBoolean(key, false)
        val row = tile(title, if (enabled) "ON • $description" else "OFF • $description")
        row.setOnClickListener {
            prefs.edit().putBoolean(key, !prefs.getBoolean(key, false)).apply()
            writeConfig()
            render()
        }
        root.addView(row, lp(top = 10))
    }

    private fun currentLabel(key: String, values: Array<String>, labels: Array<String>): String {
        val value = prefs.getString(key, values[0]) ?: values[0]
        return labels.getOrElse(values.indexOf(value).coerceAtLeast(0)) { labels[0] }
    }

    private fun tile(title: String, subtitle: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(15), dp(13), dp(15), dp(13))
        background = rounded(0xFF0E131A.toInt(), 17)
        addView(tv(title, 12f, 0xFFF4F7FA.toInt(), true))
        addView(tv(subtitle, 9.5f, 0xFF7F8A98.toInt()), lp(top = 4))
        isClickable = true
        isFocusable = true
    }

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(top)
    }

    private fun writeConfig() {
        val cfgDir = File(filesDir, "dolphin/User/Config").apply { mkdirs() }
        val backend = prefs.getString(KEY_BACKEND, "Vulkan") ?: "Vulkan"
        val resolution = prefs.getString(KEY_RESOLUTION, "1") ?: "1"
        val dualCore = prefs.getBoolean(KEY_DUAL_CORE, true)
        val backendMt = prefs.getBoolean(KEY_BACKEND_MT, true)
        val shader = prefs.getString(KEY_SHADER_MODE, "hybrid") ?: "hybrid"
        val vsync = prefs.getBoolean(KEY_VSYNC, false)
        val speed = prefs.getString(KEY_SPEED, "100") ?: "100"
        val mmu = prefs.getBoolean(KEY_MMU, false)

        File(cfgDir, "Dolphin.ini").writeText(
            """[Core]
CPUThread = ${if (dualCore) "True" else "False"}
MMU = ${if (mmu) "True" else "False"}
EmulationSpeed = ${speed.toIntOrNull()?.div(100.0) ?: 1.0}
OverclockEnable = False

[Interface]
ConfirmStop = False

[Display]
Fullscreen = True
""".trimIndent()
        )
        File(cfgDir, "GFX.ini").writeText(
            """[Settings]
Backend = $backend
InternalResolution = $resolution
VSync = ${if (vsync) "True" else "False"}
BackendMultithreading = ${if (backendMt) "True" else "False"}
ShaderCompilationMode = $shader
""".trimIndent()
        )
        File(filesDir, "dolphin/profile.txt").writeText("official-upstream|arm64-v8a|$backend|${resolution}x|dualCore=$dualCore|backendMT=$backendMt")
    }
}
