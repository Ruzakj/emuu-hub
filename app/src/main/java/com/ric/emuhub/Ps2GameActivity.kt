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

/** Minimal ARMSX2 host. BIOS-only boot intentionally follows upstream's default renderer path. */
class Ps2GameActivity : Activity(), SurfaceHolder.Callback {
    companion object {
        const val EXTRA_BIOS_ONLY = "biosOnly"
        private const val TRACE_PREFS = "ps2_runtime_trace"
        private const val TRACE_STAGE = "stage"
        private const val TRACE_ACTIVE = "active"
    }

    private lateinit var surface: SurfaceView
    private lateinit var status: TextView
    private var romPath = ""
    private var biosOnly = false
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
        getSharedPreferences(TRACE_PREFS, MODE_PRIVATE).edit()
            .putString(TRACE_STAGE, stage).putBoolean(TRACE_ACTIVE, active).commit()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        biosOnly = intent.getBooleanExtra(EXTRA_BIOS_ONLY, false)
        trace(if (biosOnly) "bios-only-activity-created" else "activity-created")
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE

        romPath = intent.getStringExtra("romPath").orEmpty()
        if (!biosOnly && (romPath.isBlank() || !File(romPath).isFile)) {
            trace("rom-missing", false)
            Toast.makeText(this, "PS2 ROM tidak ditemukan.", Toast.LENGTH_LONG).show()
            finish(); return
        }
        if (Ps2BiosActivity.selectedBios(this) == null) {
            trace("bios-missing", false)
            Toast.makeText(this, "Pilih BIOS PS2 dulu.", Toast.LENGTH_LONG).show()
            finish(); return
        }
        buildMinimalUi()
        prepareRuntime()
    }

    private fun buildMinimalUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        surface = SurfaceView(this).also { it.holder.addCallback(this) }
        root.addView(surface, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        status = TextView(this).apply {
            text = if (biosOnly) "PS2 • BIOS-only boot" else "PS2 • first-frame boot"
            textSize = 11f
            setTextColor(0xFFD0D0D0.toInt())
            setBackgroundColor(0x99000000.toInt())
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        root.addView(status, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(10) })
        root.addView(Button(this).apply { text = "EXIT"; textSize = 10f; isAllCaps = false; setOnClickListener { finish() } }, FrameLayout.LayoutParams(dp(70), dp(42), Gravity.TOP or Gravity.END).apply { topMargin = dp(10); rightMargin = dp(12) })
        setContentView(root)
    }

    private fun prepareRuntime() {
        Thread({
            try {
                trace("preparing-resources")
                val dataRoot = File(filesDir, "ps2").apply { mkdirs() }
                val resourcesDir = File(dataRoot, "resources")
                // Refresh the runtime resources on every PS2 launch. ARMSX2 ships shader/game-db
                // fixes inside the APK and stale files from an older build can crash GS startup.
                copyAssetTree("ARMSX2", resourcesDir, overwrite = true)
                val biosDir = Ps2BiosActivity.biosDir(this)
                trace("resources-ready")
                runOnUiThread { initializeCore(dataRoot, biosDir) }
            } catch (t: Throwable) {
                trace("prepare-error:${t.javaClass.simpleName}", false)
                runOnUiThread { Toast.makeText(this, "PS2 runtime gagal: ${t.message}", Toast.LENGTH_LONG).show(); finish() }
            }
        }, "ps2-prepare").start()
    }

    private fun copyAssetTree(assetPath: String, target: File, overwrite: Boolean) {
        val children = assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            if (overwrite || !target.isFile) assets.open(assetPath).use { input -> target.outputStream().use { input.copyTo(it) } }
            return
        }
        target.mkdirs()
        children.forEach { copyAssetTree("$assetPath/$it", File(target, it), overwrite) }
    }

    private fun initializeCore(dataRoot: File, biosDir: File) {
        if (initialized || isFinishing) return
        try {
            trace("before-native-load")
            NativeApp.attachContext(this)
            if (!NativeApp.isNativeReady()) error("Native ARMSX2 gagal dimuat: ${NativeApp.nativeLoadError}")

            status.text = "PS2 • initialize"
            trace(if (biosOnly) "bios-only-before-initialize" else "before-initialize")
            NativeApp.initialize(dataRoot.absolutePath, biosDir.absolutePath, Build.VERSION.SDK_INT)
            trace(if (biosOnly) "bios-only-after-initialize" else "after-initialize")

            // IMPORTANT: do not force renderVulkan() here. Current upstream ARMSX2 intentionally
            // leaves GS renderer at Auto during initialization; on Android this selects the safe
            // default renderer. Our previous forced-Vulkan call diverged from the reference app
            // before VM creation and could kill BIOS-only boot before the first frame.
            status.text = "PS2 • renderer auto"
            trace("renderer-auto")
            initialized = true
            attachNativeSurfaceIfReady()
            maybeStartVm()
        } catch (t: Throwable) {
            trace("init-error:${t.javaClass.simpleName}", false)
            Toast.makeText(this, "ARMSX2 init gagal: ${t.message ?: t.javaClass.simpleName}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun attachNativeSurfaceIfReady() {
        if (!initialized || !surfaceReady || nativeSurfaceAttached) return
        val h = surface.holder
        val w = if (surfaceWidth > 0) surfaceWidth else surface.width
        val ht = if (surfaceHeight > 0) surfaceHeight else surface.height
        if (!h.surface.isValid || w <= 0 || ht <= 0) return
        status.text = "PS2 • attach surface"
        trace("before-surface-created")
        NativeApp.onNativeSurfaceCreated()
        trace("after-surface-created")
        trace("before-surface-changed")
        NativeApp.onNativeSurfaceChanged(h.surface, w, ht)
        trace("after-surface-changed")
        nativeSurfaceAttached = true
    }

    private fun maybeStartVm() {
        if (!initialized || !surfaceReady || !nativeSurfaceAttached || !vmStarted.compareAndSet(false, true)) return
        status.text = if (biosOnly) "PS2 • boot BIOS" else "PS2 • booting VM"
        trace(if (biosOnly) "bios-only-before-run-vm" else "before-run-vm")
        val bootPath = if (biosOnly) "" else romPath
        vmThread = Thread({
            val result = runCatching { NativeApp.runVMThread(bootPath) }
            val ok = result.getOrDefault(false)
            trace(if (ok) "vm-returned-ok" else "vm-returned-false", false)
            runOnUiThread {
                if (!isFinishing && !shuttingDown.get()) {
                    if (!ok) Toast.makeText(this, if (biosOnly) "BIOS-only boot gagal." else "PS2 gagal boot.", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }, "armsx2-vm").also { it.start() }
    }

    override fun surfaceCreated(holder: SurfaceHolder) { surfaceReady = true; if (initialized) runCatching { attachNativeSurfaceIfReady() }; maybeStartVm() }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surfaceReady = true; surfaceWidth = width; surfaceHeight = height
        if (initialized) {
            if (!nativeSurfaceAttached) runCatching { attachNativeSurfaceIfReady() }
            else runCatching { NativeApp.onNativeSurfaceChanged(holder.surface, width, height) }
        }
        maybeStartVm()
    }
    override fun surfaceDestroyed(holder: SurfaceHolder) { surfaceReady = false; if (initialized && nativeSurfaceAttached) { runCatching { NativeApp.onNativeSurfaceDestroyed() }; nativeSurfaceAttached = false } }

    private fun shutdownCore() {
        if (!shuttingDown.compareAndSet(false, true)) return
        if (initialized && vmStarted.get()) runCatching { NativeApp.shutdown() }
        vmThread?.let { if (it.isAlive && it !== Thread.currentThread()) runCatching { it.join(1000) } }
        if (initialized && nativeSurfaceAttached) { runCatching { NativeApp.onNativeSurfaceDestroyed() }; nativeSurfaceAttached = false }
        if (!vmStarted.get()) trace("closed-before-vm", false)
    }

    override fun finish() { shutdownCore(); super.finish() }
    override fun onDestroy() { shutdownCore(); super.onDestroy() }
    @Deprecated("Framework compatibility") override fun onBackPressed() { finish() }
}
