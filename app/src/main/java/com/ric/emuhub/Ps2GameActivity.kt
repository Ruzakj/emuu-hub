package com.ric.emuhub

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import kr.co.iefriends.pcsx2.NativeApp
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Minimal ARMSX2 host focused on reaching the first rendered frame.
 * Performance tuning, ADPF, affinity and controller JNI are intentionally
 * deferred until the VM can boot reliably on the target device.
 */
class Ps2GameActivity : Activity(), SurfaceHolder.Callback {
    companion object {
        private const val TRACE_PREFS = "ps2_runtime_trace"
        private const val TRACE_STAGE = "stage"
        private const val TRACE_ACTIVE = "active"
    }

    private lateinit var surface: SurfaceView
    private lateinit var status: TextView
    private lateinit var romPath: String
    private var surfaceReady = false
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var initialized = false
    private var nativeSurfaceAttached = false
    private val vmStarted = AtomicBoolean(false)
    private val shuttingDown = AtomicBoolean(false)
    private var vmThread: Thread? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun trace(stage: String, active: Boolean = true) {
        getSharedPreferences(TRACE_PREFS, MODE_PRIVATE)
            .edit()
            .putString(TRACE_STAGE, stage)
            .putBoolean(TRACE_ACTIVE, active)
            .commit()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        trace("activity-created")

        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE

        romPath = intent.getStringExtra("romPath").orEmpty()
        if (romPath.isBlank() || !File(romPath).isFile) {
            trace("rom-missing", false)
            Toast.makeText(this, "PS2 ROM tidak ditemukan.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        if (Ps2BiosActivity.selectedBios(this) == null) {
            trace("bios-missing", false)
            Toast.makeText(this, "Pilih BIOS PS2 dulu dari menu PS2.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        buildMinimalUi()
        prepareRuntime()
    }

    private fun buildMinimalUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        surface = SurfaceView(this).also { it.holder.addCallback(this) }
        root.addView(
            surface,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        status = TextView(this).apply {
            text = "PS2 • first-frame boot"
            textSize = 11f
            setTextColor(0xFFD0D0D0.toInt())
            setBackgroundColor(0x99000000.toInt())
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        root.addView(
            status,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply { topMargin = dp(10) }
        )

        val exit = Button(this).apply {
            text = "EXIT"
            textSize = 10f
            isAllCaps = false
            setOnClickListener { finish() }
        }
        root.addView(
            exit,
            FrameLayout.LayoutParams(dp(70), dp(42), Gravity.TOP or Gravity.END).apply {
                topMargin = dp(10)
                rightMargin = dp(12)
            }
        )

        setContentView(root)
    }

    private fun prepareRuntime() {
        Thread({
            try {
                trace("preparing-resources")
                val dataRoot = File(filesDir, "ps2").apply { mkdirs() }
                val resourcesDir = File(dataRoot, "resources")
                if (!File(resourcesDir, "GameIndex.yaml").isFile) {
                    copyAssetTree("ARMSX2", resourcesDir)
                }
                val biosDir = Ps2BiosActivity.biosDir(this)
                if (Ps2BiosActivity.selectedBios(this) == null) error("BIOS PS2 belum siap")
                trace("resources-ready")
                runOnUiThread { initializeCore(dataRoot, biosDir) }
            } catch (t: Throwable) {
                trace("prepare-error:${t.javaClass.simpleName}", false)
                runOnUiThread {
                    Toast.makeText(
                        this,
                        "PS2 runtime gagal disiapkan: ${t.message}",
                        Toast.LENGTH_LONG
                    ).show()
                    finish()
                }
            }
        }, "ps2-prepare").start()
    }

    private fun copyAssetTree(assetPath: String, target: File) {
        val children = assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            assets.open(assetPath).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            return
        }
        target.mkdirs()
        children.forEach { child -> copyAssetTree("$assetPath/$child", File(target, child)) }
    }

    private fun initializeCore(dataRoot: File, biosDir: File) {
        if (initialized || isFinishing) return
        try {
            trace("before-native-load")
            NativeApp.attachContext(this)
            if (!NativeApp.isNativeReady()) {
                error("Native ARMSX2 gagal dimuat: ${NativeApp.nativeLoadError}")
            }

            status.text = "PS2 • initialize"
            trace("before-initialize")
            NativeApp.initialize(dataRoot.absolutePath, biosDir.absolutePath, Build.VERSION.SDK_INT)
            trace("after-initialize")

            // First-frame mode: only select the renderer. Do not apply affinity,
            // ADPF, upscale, audio or other live settings before VM creation.
            status.text = "PS2 • select Vulkan"
            trace("before-render-vulkan")
            NativeApp.renderVulkan()
            trace("after-render-vulkan")

            initialized = true
            trace("core-initialized")
            attachNativeSurfaceIfReady()
            maybeStartVm()
        } catch (t: Throwable) {
            trace("init-error:${t.javaClass.simpleName}", false)
            Toast.makeText(
                this,
                "ARMSX2 init gagal: ${t.message ?: t.javaClass.simpleName}",
                Toast.LENGTH_LONG
            ).show()
            finish()
        }
    }

    private fun attachNativeSurfaceIfReady() {
        if (!initialized || !surfaceReady || nativeSurfaceAttached) return
        val holder = surface.holder
        val w = if (surfaceWidth > 0) surfaceWidth else surface.width
        val h = if (surfaceHeight > 0) surfaceHeight else surface.height
        if (!holder.surface.isValid || w <= 0 || h <= 0) return

        status.text = "PS2 • attach surface"
        trace("before-surface-created")
        NativeApp.onNativeSurfaceCreated()
        trace("after-surface-created")
        trace("before-surface-changed")
        NativeApp.onNativeSurfaceChanged(holder.surface, w, h)
        trace("after-surface-changed")
        nativeSurfaceAttached = true
        trace("surface-attached")
    }

    private fun maybeStartVm() {
        if (!initialized || !surfaceReady || !nativeSurfaceAttached ||
            !vmStarted.compareAndSet(false, true)
        ) return

        status.text = "PS2 • booting VM"
        trace("before-run-vm")
        vmThread = Thread({
            val result = runCatching { NativeApp.runVMThread(romPath) }
            val ok = result.getOrDefault(false)
            val error = result.exceptionOrNull()
            trace(if (ok) "vm-returned-ok" else "vm-returned-false", false)
            runOnUiThread {
                if (!isFinishing && !shuttingDown.get()) {
                    if (!ok) {
                        Toast.makeText(
                            this,
                            "PS2 gagal boot${error?.message?.let { ": $it" } ?: "."}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    finish()
                }
            }
        }, "armsx2-vm").also {
            // Keep default Android scheduling for first-frame validation.
            it.start()
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        if (initialized) runCatching { attachNativeSurfaceIfReady() }
        maybeStartVm()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surfaceReady = true
        surfaceWidth = width
        surfaceHeight = height
        if (initialized) {
            if (!nativeSurfaceAttached) {
                runCatching { attachNativeSurfaceIfReady() }
            } else {
                runCatching {
                    trace("before-surface-resize")
                    NativeApp.onNativeSurfaceChanged(holder.surface, width, height)
                    trace("after-surface-resize")
                }
            }
        }
        maybeStartVm()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        if (initialized && nativeSurfaceAttached) {
            runCatching { NativeApp.onNativeSurfaceDestroyed() }
            nativeSurfaceAttached = false
        }
    }

    private fun shutdownCore() {
        if (!shuttingDown.compareAndSet(false, true)) return
        if (initialized && vmStarted.get()) runCatching { NativeApp.shutdown() }
        vmThread?.let { thread ->
            if (thread.isAlive && thread !== Thread.currentThread()) {
                runCatching { thread.join(1000) }
            }
        }
        if (initialized && nativeSurfaceAttached) {
            runCatching { NativeApp.onNativeSurfaceDestroyed() }
            nativeSurfaceAttached = false
        }
        if (!vmStarted.get()) trace("closed-before-vm", false)
    }

    override fun finish() {
        shutdownCore()
        super.finish()
    }

    override fun onDestroy() {
        shutdownCore()
        super.onDestroy()
    }

    @Deprecated("Framework compatibility")
    override fun onBackPressed() {
        finish()
    }
}
