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
        private const val KEY_SHADER_MODE = "shader_mode"
        private const val KEY_COMPILE_SHADERS = "compile_shaders"
        private const val KEY_SPEED = "speed"
        private const val KEY_MMU = "mmu"
        private const val KEY_SYNC_GPU = "sync_gpu"
        private const val KEY_WIDESCREEN = "widescreen"
        private const val KEY_PAL60 = "pal60"
        private const val KEY_SCALED_EFB = "scaled_efb"
        private const val KEY_DISC_SPEED = "disc_speed"
        private const val KEY_VSYNC = "vsync"
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
                .putString(KEY_RESOLUTION, "2")
                .putBoolean(KEY_DUAL_CORE, true)
                .putString(KEY_SHADER_MODE, "hybrid")
                .putBoolean(KEY_COMPILE_SHADERS, true)
                .putString(KEY_SPEED, "100")
                .putBoolean(KEY_MMU, false)
                .putString(KEY_SYNC_GPU, "idle")
                .putBoolean(KEY_WIDESCREEN, true)
                .putBoolean(KEY_PAL60, true)
                .putBoolean(KEY_SCALED_EFB, true)
                .putBoolean(KEY_DISC_SPEED, true)
                .putBoolean(KEY_VSYNC, false)
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
        root.addView(tv("Dolphin Native 2606a profile", 10f, 0xFF8793A4.toInt()), lp(top = 4))
        root.addView(tv("Preset ini mengikuti setting yang terbukti lancar di Dolphin Android pada perangkat target: Vulkan, Dual Core, JIT ARM64, 2× Native, Hybrid Ubershaders, shader precompile, GPU sync On Idle Skipping, MMU off.", 11f, 0xFFAAB4C2.toInt()), lp(top = 12))

        addSection("GENERAL")
        addToggle("Dual Core (speedhack)", KEY_DUAL_CORE, "Split workload CPU; default ON.")
        addChoice("Speed Limit", KEY_SPEED, arrayOf("100", "90", "80", "0"), arrayOf("100% • Normal", "90%", "80%", "0% • Unlimited"))
        addToggle("MMU", KEY_MMU, "Compatibility only; default OFF.")
        addChoice("Synchronize GPU Thread", KEY_SYNC_GPU, arrayOf("never", "idle", "always"), arrayOf("Never", "On Idle Skipping • Recommended", "Always"))
        addToggle("Emulate Disc Speed", KEY_DISC_SPEED, "Default ON for compatibility.")

        addSection("GRAPHICS")
        addChoice("Video Backend", KEY_BACKEND, arrayOf("Vulkan", "OpenGL"), arrayOf("Vulkan • Target profile", "OpenGL • Compatibility fallback"))
        addChoice("Internal Resolution", KEY_RESOLUTION, arrayOf("1", "2", "3"), arrayOf("1× Native • 640×528", "2× Native • 1280×1056", "3× Native • 1920×1584"))
        addChoice("Shader Compilation", KEY_SHADER_MODE, arrayOf("hybrid", "specialized", "exclusive"), arrayOf("Hybrid Ubershaders • Target profile", "Specialized", "Exclusive Ubershaders"))
        addToggle("Compile Shaders Before Starting", KEY_COMPILE_SHADERS, "Reduces early shader stutter; default ON.")
        addToggle("Scaled EFB Copy", KEY_SCALED_EFB, "Matches the tested Dolphin profile.")
        addToggle("V-Sync", KEY_VSYNC, "Default OFF for lower latency/overhead.")

        addSection("WII")
        addToggle("Widescreen", KEY_WIDESCREEN, "Force/support 16:9 where available.")
        addToggle("Use PAL60 Mode", KEY_PAL60, "60 Hz for supported PAL titles.")

        root.addView(tv("CPU Core is fixed to JIT Recompiler for ARM64 in the native engine profile. Anti-aliasing, anisotropic filtering, texture filtering and post-processing stay at default/off unless a per-game override is added.", 10f, 0xFF6F7A89.toInt()), lp(top = 20))
        scroll.addView(root)
        setContentView(scroll)
    }

    private fun addSection(label: String) {
        root.addView(tv(label, 10f, 0xFF8EA2BE.toInt(), true).apply { letterSpacing = 0.14f }, lp(top = 20))
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
        val resolution = prefs.getString(KEY_RESOLUTION, "2") ?: "2"
        val dualCore = prefs.getBoolean(KEY_DUAL_CORE, true)
        val shader = prefs.getString(KEY_SHADER_MODE, "hybrid") ?: "hybrid"
        val compileShaders = prefs.getBoolean(KEY_COMPILE_SHADERS, true)
        val speed = prefs.getString(KEY_SPEED, "100") ?: "100"
        val mmu = prefs.getBoolean(KEY_MMU, false)
        val syncGpu = prefs.getString(KEY_SYNC_GPU, "idle") ?: "idle"
        val widescreen = prefs.getBoolean(KEY_WIDESCREEN, true)
        val pal60 = prefs.getBoolean(KEY_PAL60, true)
        val scaledEfb = prefs.getBoolean(KEY_SCALED_EFB, true)
        val discSpeed = prefs.getBoolean(KEY_DISC_SPEED, true)
        val vsync = prefs.getBoolean(KEY_VSYNC, false)

        File(cfgDir, "Dolphin.ini").writeText(
            """[Core]
CPUThread = ${if (dualCore) "True" else "False"}
CPUCore = JITARM64
MMU = ${if (mmu) "True" else "False"}
EmulationSpeed = ${speed.toIntOrNull()?.let { if (it == 0) 0.0 else it / 100.0 } ?: 1.0}
SyncOnSkipIdle = ${if (syncGpu == "idle") "True" else "False"}
FastDiscSpeed = ${if (discSpeed) "False" else "True"}
PAL60 = ${if (pal60) "True" else "False"}

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
ShaderCompilationMode = $shader
WaitForShadersBeforeStarting = ${if (compileShaders) "True" else "False"}
wideScreenHack = ${if (widescreen) "True" else "False"}
EFBScaledCopy = ${if (scaledEfb) "True" else "False"}
MSAA = 1
MaxAnisotropy = 1
PostProcessingShader = 
""".trimIndent()
        )
        File(filesDir, "dolphin/profile.txt").writeText(
            "native-2606a|arm64-v8a|backend=$backend|${resolution}x|dualCore=$dualCore|mmu=$mmu|shader=$shader|precompile=$compileShaders|syncGpu=$syncGpu|scaledEfb=$scaledEfb|pal60=$pal60"
        )
    }
}
