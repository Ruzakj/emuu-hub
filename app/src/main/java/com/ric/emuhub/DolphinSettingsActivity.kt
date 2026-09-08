package com.ric.emuhub

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.util.LinkedHashMap

class DolphinSettingsActivity : Activity() {
    data class ApplyResult(
        val userDir: File,
        val configDir: File,
        val nativeApplied: Boolean,
        val nativeUserDir: String?,
        val error: String?
    )

    companion object {
        private const val PREFS = "dolphin_engine_settings"
        private const val KEY_BACKEND = "backend"
        private const val KEY_RESOLUTION = "resolution"
        private const val KEY_DUAL_CORE = "dual_core"
        private const val KEY_FASTMEM = "fastmem"
        private const val KEY_DSP_HLE = "dsp_hle"
        private const val KEY_SHADER_MODE = "shader_mode"
        private const val KEY_COMPILE_SHADERS = "compile_shaders"
        private const val KEY_SPEED = "speed"
        private const val KEY_OVERCLOCK_ENABLE = "overclock_enable"
        private const val KEY_OVERCLOCK = "overclock"
        private const val KEY_MMU = "mmu"
        private const val KEY_SYNC_GPU = "sync_gpu"
        private const val KEY_WIDESCREEN = "widescreen"
        private const val KEY_PAL60 = "pal60"
        private const val KEY_PROGRESSIVE = "progressive"
        private const val KEY_SCALED_EFB = "scaled_efb"
        private const val KEY_FAST_DISC = "fast_disc"
        private const val KEY_VSYNC = "vsync"
        private const val KEY_MSAA = "msaa"
        private const val KEY_ANISOTROPY = "anisotropy"
        private const val KEY_FAST_DEPTH = "fast_depth"
        private const val KEY_GPU_TEX_DECODE = "gpu_tex_decode"
        private const val KEY_BACKEND_MT = "backend_mt"
        private const val KEY_EFB_ACCESS = "efb_access"
        private const val KEY_DEFER_EFB = "defer_efb"
        private const val KEY_FAST_TEX_SAMPLING = "fast_tex_sampling"
        private const val KEY_SAVE_TEX_CACHE = "save_tex_cache"
        private const val KEY_SAVE_STATES = "save_states"
        private const val KEY_HUD = "runtime_hud"
        private const val KEY_AUDIO_VOLUME = "audio_volume"
        private const val KEY_AUDIO_BUFFER = "audio_buffer"
        private const val KEY_LAST_APPLY = "last_apply"
        private const val KEY_LAST_APPLY_NATIVE = "last_apply_native"
        private const val KEY_LAST_ERROR = "last_error"

        private fun prefs(context: Context) = context.getSharedPreferences(PREFS, MODE_PRIVATE)

        @JvmStatic
        fun ensureDefaults(context: Context) {
            val p = prefs(context)
            if (p.contains(KEY_BACKEND)) return
            p.edit()
                .putString(KEY_BACKEND, "Vulkan")
                .putString(KEY_RESOLUTION, "2")
                .putBoolean(KEY_DUAL_CORE, true)
                .putBoolean(KEY_FASTMEM, true)
                .putBoolean(KEY_DSP_HLE, true)
                .putString(KEY_SHADER_MODE, "2")
                .putBoolean(KEY_COMPILE_SHADERS, true)
                .putString(KEY_SPEED, "100")
                .putBoolean(KEY_OVERCLOCK_ENABLE, false)
                .putString(KEY_OVERCLOCK, "100")
                .putBoolean(KEY_MMU, false)
                .putString(KEY_SYNC_GPU, "idle")
                .putBoolean(KEY_WIDESCREEN, false)
                .putBoolean(KEY_PAL60, true)
                .putBoolean(KEY_PROGRESSIVE, true)
                .putBoolean(KEY_SCALED_EFB, true)
                .putBoolean(KEY_FAST_DISC, false)
                .putBoolean(KEY_VSYNC, false)
                .putString(KEY_MSAA, "1")
                .putString(KEY_ANISOTROPY, "0")
                .putBoolean(KEY_FAST_DEPTH, true)
                .putBoolean(KEY_GPU_TEX_DECODE, false)
                .putBoolean(KEY_BACKEND_MT, true)
                .putBoolean(KEY_EFB_ACCESS, false)
                .putBoolean(KEY_DEFER_EFB, true)
                .putBoolean(KEY_FAST_TEX_SAMPLING, true)
                .putBoolean(KEY_SAVE_TEX_CACHE, true)
                .putBoolean(KEY_SAVE_STATES, true)
                .putBoolean(KEY_HUD, true)
                .putString(KEY_AUDIO_VOLUME, "100")
                .putString(KEY_AUDIO_BUFFER, "80")
                .apply()
        }

        /** Dolphin 2606a DirectoryInitialization uses Context.getExternalFilesDir(null)
         * directly as its native User directory. Config therefore belongs in User/Config,
         * i.e. <externalFilesDir>/Config, not filesDir/dolphin/User/Config. */
        @JvmStatic
        fun userDir(context: Context): File =
            context.getExternalFilesDir(null) ?: File(context.filesDir, "dolphin-user")

        @JvmStatic
        fun applyPersistedConfig(context: Context, tryNative: Boolean = true): ApplyResult {
            ensureDefaults(context)
            val p = prefs(context)
            val user = userDir(context).apply { mkdirs() }
            val config = File(user, "Config").apply { mkdirs() }

            // Keep official Dolphin persistence locations stable across app restarts/updates.
            File(user, "StateSaves").mkdirs()
            File(user, "GC").mkdirs()
            File(user, "Wii").mkdirs()

            val backend = p.getString(KEY_BACKEND, "Vulkan") ?: "Vulkan"
            val resolution = p.getString(KEY_RESOLUTION, "2") ?: "2"
            val dualCore = p.getBoolean(KEY_DUAL_CORE, true)
            val fastmem = p.getBoolean(KEY_FASTMEM, true)
            val dspHle = p.getBoolean(KEY_DSP_HLE, true)
            val shaderMode = p.getString(KEY_SHADER_MODE, "2") ?: "2"
            val compileShaders = p.getBoolean(KEY_COMPILE_SHADERS, true)
            val speedPercent = p.getString(KEY_SPEED, "100")?.toIntOrNull() ?: 100
            val overclockEnable = p.getBoolean(KEY_OVERCLOCK_ENABLE, false)
            val overclockPercent = p.getString(KEY_OVERCLOCK, "100")?.toIntOrNull() ?: 100
            val mmu = p.getBoolean(KEY_MMU, false)
            val syncMode = p.getString(KEY_SYNC_GPU, "idle") ?: "idle"
            val widescreen = p.getBoolean(KEY_WIDESCREEN, false)
            val pal60 = p.getBoolean(KEY_PAL60, true)
            val progressive = p.getBoolean(KEY_PROGRESSIVE, true)
            val scaledEfb = p.getBoolean(KEY_SCALED_EFB, true)
            val fastDisc = p.getBoolean(KEY_FAST_DISC, false)
            val vsync = p.getBoolean(KEY_VSYNC, false)
            val msaa = p.getString(KEY_MSAA, "1")?.toIntOrNull() ?: 1
            val anisotropy = p.getString(KEY_ANISOTROPY, "0")?.toIntOrNull() ?: 0
            val fastDepth = p.getBoolean(KEY_FAST_DEPTH, true)
            val gpuTexDecode = p.getBoolean(KEY_GPU_TEX_DECODE, false)
            val backendMt = p.getBoolean(KEY_BACKEND_MT, true)
            val efbAccess = p.getBoolean(KEY_EFB_ACCESS, false)
            val deferEfb = p.getBoolean(KEY_DEFER_EFB, true)
            val fastTextureSampling = p.getBoolean(KEY_FAST_TEX_SAMPLING, true)
            val saveTextureCache = p.getBoolean(KEY_SAVE_TEX_CACHE, true)
            val saveStates = p.getBoolean(KEY_SAVE_STATES, true)
            val hud = p.getBoolean(KEY_HUD, true)
            val volume = p.getString(KEY_AUDIO_VOLUME, "100")?.toIntOrNull()?.coerceIn(0, 100) ?: 100
            val audioBuffer = p.getString(KEY_AUDIO_BUFFER, "80")?.toIntOrNull()?.coerceIn(32, 512) ?: 80

            // Pre-seed the exact files Dolphin reads. We merge keys instead of overwriting
            // the whole INI, preserving controller mappings and settings made in Dolphin UI.
            mergeIni(
                File(config, "Dolphin.ini"),
                linkedMapOf(
                    "Core" to linkedMapOf(
                        "GFXBackend" to backend,
                        "CPUThread" to bool(dualCore),
                        "Fastmem" to bool(fastmem),
                        "DSPHLE" to bool(dspHle),
                        "MMU" to bool(mmu),
                        "EmulationSpeed" to if (speedPercent == 0) "0.0" else "%.2f".format(java.util.Locale.US, speedPercent / 100f),
                        "OverclockEnable" to bool(overclockEnable),
                        "Overclock" to "%.2f".format(java.util.Locale.US, overclockPercent / 100f),
                        "SyncOnSkipIdle" to bool(syncMode == "idle"),
                        "SyncGPU" to bool(syncMode == "full"),
                        "FastDiscSpeed" to bool(fastDisc),
                        "EnableSaveStates" to bool(saveStates)
                    ),
                    "DSP" to linkedMapOf(
                        "Volume" to volume.toString(),
                        "AudioBufferSize" to audioBuffer.toString()
                    )
                )
            )
            mergeIni(
                File(config, "GFX.ini"),
                linkedMapOf(
                    "Settings" to linkedMapOf(
                        "InternalResolution" to resolution,
                        "VSync" to bool(vsync),
                        "ShaderCompilationMode" to shaderMode,
                        "WaitForShadersBeforeStarting" to bool(compileShaders),
                        "MSAA" to msaa.toString(),
                        "ShowFPS" to bool(hud),
                        "ShowVPS" to bool(hud),
                        "ShowSpeed" to bool(hud),
                        "OverlayStats" to bool(hud),
                        "EnableGPUTextureDecoding" to bool(gpuTexDecode),
                        "FastDepthCalc" to bool(fastDepth),
                        "BackendMultithreading" to bool(backendMt),
                        "SaveTextureCacheToState" to bool(saveTextureCache)
                    ),
                    "Enhancements" to linkedMapOf(
                        "MaxAnisotropy" to anisotropy.toString()
                    ),
                    "Hacks" to linkedMapOf(
                        "EFBAccessEnable" to bool(efbAccess),
                        "DeferEFBCopies" to bool(deferEfb),
                        "EFBScaledCopy" to bool(scaledEfb),
                        "FastTextureSampling" to bool(fastTextureSampling)
                    )
                )
            )

            var nativeApplied = false
            var nativeUser: String? = null
            var error: String? = null
            if (tryNative) {
                try {
                    nativeUser = applyViaNativeConfig(
                        backend, resolution.toIntOrNull() ?: 2, dualCore, fastmem, dspHle,
                        shaderMode.toIntOrNull() ?: 2, compileShaders, speedPercent,
                        overclockEnable, overclockPercent, mmu, syncMode, widescreen, pal60,
                        progressive, scaledEfb, fastDisc, vsync, msaa, anisotropy, fastDepth,
                        gpuTexDecode, backendMt, efbAccess, deferEfb, fastTextureSampling,
                        saveTextureCache, saveStates, hud, volume, audioBuffer
                    )
                    nativeApplied = true
                } catch (t: Throwable) {
                    error = "${t.javaClass.simpleName}: ${t.message ?: "native apply gagal"}"
                }
            }

            File(user, "emuhub-runtime-profile.txt").writeText(
                buildString {
                    appendLine("engine=dolphin-2606a")
                    appendLine("userDir=${user.absolutePath}")
                    appendLine("nativeUserDir=${nativeUser ?: "not-ready"}")
                    appendLine("nativeApplied=$nativeApplied")
                    appendLine("backend=$backend")
                    appendLine("resolution=${resolution}x")
                    appendLine("dualCore=$dualCore")
                    appendLine("shaderMode=$shaderMode")
                    appendLine("hud=$hud")
                    appendLine("saveStates=$saveStates")
                    appendLine("stateDir=${File(user, "StateSaves").absolutePath}")
                    appendLine("timestamp=${System.currentTimeMillis()}")
                    if (error != null) appendLine("error=$error")
                }
            )
            p.edit()
                .putLong(KEY_LAST_APPLY, System.currentTimeMillis())
                .putBoolean(KEY_LAST_APPLY_NATIVE, nativeApplied)
                .putString(KEY_LAST_ERROR, error)
                .apply()

            return ApplyResult(user, config, nativeApplied, nativeUser, error)
        }

        private fun applyViaNativeConfig(
            backend: String,
            resolution: Int,
            dualCore: Boolean,
            fastmem: Boolean,
            dspHle: Boolean,
            shaderMode: Int,
            compileShaders: Boolean,
            speedPercent: Int,
            overclockEnable: Boolean,
            overclockPercent: Int,
            mmu: Boolean,
            syncMode: String,
            widescreen: Boolean,
            pal60: Boolean,
            progressive: Boolean,
            scaledEfb: Boolean,
            fastDisc: Boolean,
            vsync: Boolean,
            msaa: Int,
            anisotropy: Int,
            fastDepth: Boolean,
            gpuTexDecode: Boolean,
            backendMt: Boolean,
            efbAccess: Boolean,
            deferEfb: Boolean,
            fastTextureSampling: Boolean,
            saveTextureCache: Boolean,
            saveStates: Boolean,
            hud: Boolean,
            volume: Int,
            audioBuffer: Int
        ): String {
            val nativeLibrary = Class.forName("org.dolphinemu.dolphinemu.NativeLibrary")
            val user = nativeLibrary.getMethod("GetUserDirectory").invoke(null) as String
            require(user.isNotBlank()) { "Dolphin native user directory kosong" }

            val settingsClass = Class.forName("org.dolphinemu.dolphinemu.features.settings.model.Settings")
            val settings = settingsClass.getConstructor().newInstance()
            settingsClass.getMethod("loadSettings").invoke(settings)

            setString(settingsClass, settings, "MAIN_GFX_BACKEND", backend)
            setBoolean(settingsClass, settings, "MAIN_CPU_THREAD", dualCore)
            setBoolean(settingsClass, settings, "MAIN_FASTMEM", fastmem)
            setBoolean(settingsClass, settings, "MAIN_DSP_HLE", dspHle)
            setBoolean(settingsClass, settings, "MAIN_MMU", mmu)
            setBoolean(settingsClass, settings, "MAIN_SYNC_ON_SKIP_IDLE", syncMode == "idle")
            setBoolean(settingsClass, settings, "MAIN_SYNC_GPU", syncMode == "full")
            setBoolean(settingsClass, settings, "MAIN_FAST_DISC_SPEED", fastDisc)
            setBoolean(settingsClass, settings, "MAIN_OVERCLOCK_ENABLE", overclockEnable)
            setBoolean(settingsClass, settings, "MAIN_ENABLE_SAVESTATES", saveStates)
            setFloat(settingsClass, settings, "MAIN_EMULATION_SPEED", if (speedPercent == 0) 0f else speedPercent / 100f)
            setFloat(settingsClass, settings, "MAIN_OVERCLOCK", overclockPercent / 100f)
            setInt(settingsClass, settings, "MAIN_AUDIO_VOLUME", volume)
            setInt(settingsClass, settings, "MAIN_AUDIO_BUFFER_SIZE", audioBuffer)

            setInt(settingsClass, settings, "GFX_EFB_SCALE", resolution.coerceIn(1, 4))
            setInt(settingsClass, settings, "GFX_SHADER_COMPILATION_MODE", shaderMode.coerceIn(0, 3))
            setInt(settingsClass, settings, "GFX_MSAA", msaa)
            setInt(settingsClass, settings, "GFX_ENHANCE_MAX_ANISOTROPY", anisotropy)
            setBoolean(settingsClass, settings, "GFX_VSYNC", vsync)
            setBoolean(settingsClass, settings, "GFX_WIDESCREEN_HACK", widescreen)
            setBoolean(settingsClass, settings, "GFX_WAIT_FOR_SHADERS_BEFORE_STARTING", compileShaders)
            setBoolean(settingsClass, settings, "GFX_SHOW_FPS", hud)
            setBoolean(settingsClass, settings, "GFX_SHOW_VPS", hud)
            setBoolean(settingsClass, settings, "GFX_SHOW_SPEED", hud)
            setBoolean(settingsClass, settings, "GFX_OVERLAY_STATS", hud)
            setBoolean(settingsClass, settings, "GFX_ENABLE_GPU_TEXTURE_DECODING", gpuTexDecode)
            setBoolean(settingsClass, settings, "GFX_FAST_DEPTH_CALC", fastDepth)
            setBoolean(settingsClass, settings, "GFX_BACKEND_MULTITHREADING", backendMt)
            setBoolean(settingsClass, settings, "GFX_SAVE_TEXTURE_CACHE_TO_STATE", saveTextureCache)
            setBoolean(settingsClass, settings, "GFX_HACK_EFB_ACCESS_ENABLE", efbAccess)
            setBoolean(settingsClass, settings, "GFX_HACK_DEFER_EFB_COPIES", deferEfb)
            setBoolean(settingsClass, settings, "GFX_HACK_COPY_EFB_SCALED", scaledEfb)
            setBoolean(settingsClass, settings, "GFX_HACK_FAST_TEXTURE_SAMPLING", fastTextureSampling)
            setBoolean(settingsClass, settings, "SYSCONF_PAL60", pal60)
            setBoolean(settingsClass, settings, "SYSCONF_PROGRESSIVE_SCAN", progressive)

            settingsClass.getMethod("saveSettings").invoke(settings)
            runCatching { settingsClass.getMethod("close").invoke(settings) }
            nativeLibrary.getMethod("ReloadConfig").invoke(null)
            return nativeLibrary.getMethod("GetUserDirectory").invoke(null) as String
        }

        private fun enumConstant(className: String, name: String): Any {
            val cls = Class.forName(className)
            return cls.enumConstants.firstOrNull { (it as Enum<*>).name == name }
                ?: throw IllegalArgumentException("Dolphin setting tidak ditemukan: $name")
        }

        private fun setBoolean(settingsClass: Class<*>, settings: Any, name: String, value: Boolean) {
            val item = enumConstant("org.dolphinemu.dolphinemu.features.settings.model.BooleanSetting", name)
            item.javaClass.getMethod("setBoolean", settingsClass, Boolean::class.javaPrimitiveType).invoke(item, settings, value)
        }

        private fun setInt(settingsClass: Class<*>, settings: Any, name: String, value: Int) {
            val item = enumConstant("org.dolphinemu.dolphinemu.features.settings.model.IntSetting", name)
            item.javaClass.getMethod("setInt", settingsClass, Int::class.javaPrimitiveType).invoke(item, settings, value)
        }

        private fun setFloat(settingsClass: Class<*>, settings: Any, name: String, value: Float) {
            val item = enumConstant("org.dolphinemu.dolphinemu.features.settings.model.FloatSetting", name)
            item.javaClass.getMethod("setFloat", settingsClass, Float::class.javaPrimitiveType).invoke(item, settings, value)
        }

        private fun setString(settingsClass: Class<*>, settings: Any, name: String, value: String) {
            val item = enumConstant("org.dolphinemu.dolphinemu.features.settings.model.StringSetting", name)
            item.javaClass.getMethod("setString", settingsClass, String::class.java).invoke(item, settings, value)
        }

        private fun bool(value: Boolean) = if (value) "True" else "False"

        private fun mergeIni(file: File, changes: LinkedHashMap<String, LinkedHashMap<String, String>>) {
            val data = LinkedHashMap<String, LinkedHashMap<String, String>>()
            var current = ""
            if (file.isFile) {
                file.readLines().forEach { raw ->
                    val line = raw.trim()
                    if (line.startsWith("[") && line.endsWith("]")) {
                        current = line.substring(1, line.length - 1)
                        data.getOrPut(current) { LinkedHashMap() }
                    } else if (line.isNotEmpty() && !line.startsWith("#") && !line.startsWith(";") && "=" in line) {
                        val parts = line.split("=", limit = 2)
                        data.getOrPut(current) { LinkedHashMap() }[parts[0].trim()] = parts[1].trim()
                    }
                }
            }
            changes.forEach { (section, values) ->
                val target = data.getOrPut(section) { LinkedHashMap() }
                values.forEach { (k, v) -> target[k] = v }
            }
            file.parentFile?.mkdirs()
            file.writeText(buildString {
                data.forEach { (section, values) ->
                    if (section.isNotBlank()) appendLine("[$section]")
                    values.forEach { (k, v) -> appendLine("$k = $v") }
                    appendLine()
                }
            })
        }
    }

    private val p by lazy { prefs(this) }
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
        ensureDefaults(this)
        applyPersistedConfig(this, tryNative = nativeDirectoriesReady())
        render()
    }

    private fun nativeDirectoriesReady(): Boolean = runCatching {
        val cls = Class.forName("org.dolphinemu.dolphinemu.utils.DirectoryInitialization")
        cls.getMethod("areDolphinDirectoriesReady").invoke(null) as Boolean
    }.getOrDefault(false)

    private fun render() {
        window.statusBarColor = 0xFF030406.toInt()
        window.navigationBarColor = 0xFF030406.toInt()
        val scroll = ScrollView(this).apply { setBackgroundColor(0xFF030406.toInt()) }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(32))
        }
        root.addView(tv("GAMECUBE / WII", 23f, 0xFFFFFFFF.toInt(), true))
        root.addView(tv("Dolphin Native 2606a • Manual Runtime Settings", 10f, 0xFF8793A4.toInt()), lp(top = 4))

        val nativeOk = p.getBoolean(KEY_LAST_APPLY_NATIVE, false)
        val lastError = p.getString(KEY_LAST_ERROR, null)
        val status = if (nativeOk) "NATIVE SYNC ✓" else "INI READY • native sync saat launch"
        root.addView(tv("$status\nUser: ${userDir(this).absolutePath}${if (!lastError.isNullOrBlank()) "\nLast: $lastError" else ""}", 10f, if (nativeOk) 0xFF79D69A.toInt() else 0xFFF0C674.toInt()), lp(top = 12))
        root.addView(tv("HUD menggunakan overlay statistik milik Dolphin sendiri. Jika FPS/VPS/Speed tampil saat game berjalan, setting ini sudah masuk ke renderer native, bukan sekadar UI Emu Hub.", 11f, 0xFFAAB4C2.toInt()), lp(top = 12))

        addSection("RUNTIME VERIFICATION")
        addToggle("Dolphin Runtime HUD", KEY_HUD, "FPS + VPS + Speed + renderer stats. Default ON untuk verifikasi.")
        addToggle("Enable Save States", KEY_SAVE_STATES, "Mengaktifkan Save/Load State resmi Dolphin dan StateSaves persistence.")

        addSection("CPU / CORE")
        addToggle("Dual Core", KEY_DUAL_CORE, "CPUThread. Lebih cepat pada mayoritas game.")
        addToggle("Fastmem", KEY_FASTMEM, "JIT fast memory path.")
        addToggle("DSP HLE", KEY_DSP_HLE, "Audio HLE; performa lebih ringan.")
        addChoice("Speed Limit", KEY_SPEED, arrayOf("100", "90", "80", "0"), arrayOf("100% • Normal", "90%", "80%", "Unlimited"))
        addToggle("CPU Overclock", KEY_OVERCLOCK_ENABLE, "Aktifkan manual CPU clock override.")
        addChoice("CPU Clock", KEY_OVERCLOCK, arrayOf("80", "100", "110", "120", "130", "150"), arrayOf("80%", "100%", "110%", "120%", "130%", "150%"))
        addToggle("MMU", KEY_MMU, "Compatibility; biasanya OFF lebih cepat.")
        addChoice("Synchronize GPU Thread", KEY_SYNC_GPU, arrayOf("off", "idle", "full"), arrayOf("Off", "On Idle Skipping • Recommended", "Full Sync • Compatibility"))
        addToggle("Fast Disc Speed", KEY_FAST_DISC, "Lewati timing disc asli untuk loading lebih cepat; bisa mengganggu game tertentu.")

        addSection("GRAPHICS")
        addChoice("Video Backend", KEY_BACKEND, arrayOf("Vulkan", "OGL"), arrayOf("Vulkan • Recommended", "OpenGL • Compatibility"))
        addChoice("Internal Resolution", KEY_RESOLUTION, arrayOf("1", "2", "3", "4"), arrayOf("1× Native", "2× Native", "3× Native", "4× Native"))
        addChoice("Shader Compilation", KEY_SHADER_MODE, arrayOf("0", "1", "2", "3"), arrayOf("Specialized", "Exclusive Ubershaders", "Hybrid Ubershaders • Recommended", "Skip Drawing"))
        addToggle("Compile Shaders Before Starting", KEY_COMPILE_SHADERS, "Mengurangi stutter shader awal.")
        addChoice("MSAA", KEY_MSAA, arrayOf("1", "2", "4", "8"), arrayOf("Off / 1×", "2×", "4×", "8×"))
        addChoice("Anisotropic Filtering", KEY_ANISOTROPY, arrayOf("0", "2", "4", "8", "16"), arrayOf("Default", "2×", "4×", "8×", "16×"))
        addToggle("V-Sync", KEY_VSYNC, "Sinkronisasi frame; OFF untuk latency lebih rendah.")
        addToggle("Backend Multithreading", KEY_BACKEND_MT, "Multi-thread renderer backend.")
        addToggle("GPU Texture Decoding", KEY_GPU_TEX_DECODE, "Decode texture via GPU bila backend/device mendukung.")
        addToggle("Fast Depth Calculation", KEY_FAST_DEPTH, "Fast depth path.")
        addToggle("Scaled EFB Copy", KEY_SCALED_EFB, "EFB copy mengikuti internal resolution.")
        addToggle("EFB Access", KEY_EFB_ACCESS, "Compatibility feature; aktifkan hanya jika game membutuhkan.")
        addToggle("Defer EFB Copies", KEY_DEFER_EFB, "Kurangi overhead EFB pada game yang kompatibel.")
        addToggle("Fast Texture Sampling", KEY_FAST_TEX_SAMPLING, "Fast texture sampling hack.")
        addToggle("Save Texture Cache in State", KEY_SAVE_TEX_CACHE, "Membantu konsistensi visual setelah Load State.")

        addSection("AUDIO")
        addChoice("Volume", KEY_AUDIO_VOLUME, arrayOf("0", "25", "50", "75", "100"), arrayOf("Mute", "25%", "50%", "75%", "100%"))
        addChoice("Audio Buffer", KEY_AUDIO_BUFFER, arrayOf("48", "64", "80", "96", "128", "160"), arrayOf("48 ms • Low latency", "64 ms", "80 ms • Default", "96 ms", "128 ms", "160 ms • Safer"))

        addSection("WII / DISPLAY")
        addToggle("Widescreen Hack", KEY_WIDESCREEN, "Force/support widescreen pada game yang cocok.")
        addToggle("PAL60", KEY_PAL60, "SYSCONF PAL60 resmi Dolphin.")
        addToggle("Progressive Scan", KEY_PROGRESSIVE, "SYSCONF progressive scan resmi Dolphin.")

        root.addView(tv("Save game GameCube/Wii, NAND dan savestate disimpan di User Directory Dolphin milik Emu Hub. File tidak ditulis ke cache dan tidak dihapus saat aplikasi ditutup.", 10f, 0xFF6F7A89.toInt()), lp(top = 20))
        scroll.addView(root)
        setContentView(scroll)
    }

    private fun addSection(label: String) {
        root.addView(tv(label, 10f, 0xFF8EA2BE.toInt(), true).apply { letterSpacing = 0.14f }, lp(top = 20))
    }

    private fun addChoice(title: String, key: String, values: Array<String>, labels: Array<String>) {
        val row = tile(title, currentLabel(key, values, labels))
        row.setOnClickListener {
            val current = p.getString(key, values[0]) ?: values[0]
            val checked = values.indexOf(current).coerceAtLeast(0)
            AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(labels, checked) { d, which ->
                    p.edit().putString(key, values[which]).apply()
                    applyPersistedConfig(this, tryNative = nativeDirectoriesReady())
                    d.dismiss()
                    render()
                }
                .setNegativeButton("Batal", null)
                .show()
        }
        root.addView(row, lp(top = 10))
    }

    private fun addToggle(title: String, key: String, description: String) {
        val enabled = p.getBoolean(key, false)
        val row = tile(title, if (enabled) "ON • $description" else "OFF • $description")
        row.setOnClickListener {
            p.edit().putBoolean(key, !p.getBoolean(key, false)).apply()
            applyPersistedConfig(this, tryNative = nativeDirectoriesReady())
            render()
        }
        root.addView(row, lp(top = 10))
    }

    private fun currentLabel(key: String, values: Array<String>, labels: Array<String>): String {
        val value = p.getString(key, values[0]) ?: values[0]
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
}
