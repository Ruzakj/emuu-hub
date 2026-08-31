package com.ric.emuhub

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
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

/** Dedicated Android-native ARMSX2 host. Kept separate from the libretro/GLES path. */
class Ps2GameActivity : Activity(), SurfaceHolder.Callback {
    private lateinit var surface: SurfaceView
    private lateinit var root: FrameLayout
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )

        romPath = intent.getStringExtra("romPath").orEmpty()
        if (romPath.isBlank() || !File(romPath).isFile) {
            Toast.makeText(this, "PS2 ROM tidak ditemukan.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val bios = Ps2BiosActivity.selectedBios(this)
        if (bios == null) {
            Toast.makeText(this, "Pilih BIOS PS2 dulu dari menu PS2.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        buildUi()
        prepareRuntime()
    }

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        surface = SurfaceView(this).also { it.holder.addCallback(this) }
        root.addView(surface, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        status = TextView(this).apply {
            text = "PS2 • preparing ARMSX2"
            textSize = 11f
            setTextColor(0xFFCCCCCC.toInt())
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = rounded(0x99000000.toInt(), 12)
        }
        root.addView(status, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(10) })

        addControllerOverlay()
        setContentView(root)
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        setStroke(dp(1), 0x55FFFFFF)
    }

    private fun padButton(label: String, binding: Int, size: Int = 50): Button {
        return Button(this).apply {
            text = label
            textSize = 12f
            setTextColor(Color.WHITE)
            isAllCaps = false
            setPadding(0, 0, 0, 0)
            background = rounded(0x55000000, size / 2)
            setOnTouchListener { _, event ->
                if (!initialized || !vmStarted.get()) return@setOnTouchListener true
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                        runCatching { NativeApp.setPadButton(binding, 255, true) }
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                        runCatching { NativeApp.setPadButton(binding, 255, false) }
                        true
                    }
                    else -> true
                }
            }
        }
    }

    private fun addControllerOverlay() {
        fun add(label: String, binding: Int, gravity: Int, left: Int = 0, top: Int = 0, right: Int = 0, bottom: Int = 0, size: Int = 50) {
            root.addView(padButton(label, binding, size), FrameLayout.LayoutParams(dp(size), dp(size), gravity).apply {
                leftMargin = dp(left); topMargin = dp(top); rightMargin = dp(right); bottomMargin = dp(bottom)
            })
        }

        add("↑", 0, Gravity.BOTTOM or Gravity.LEFT, left = 64, bottom = 116)
        add("→", 1, Gravity.BOTTOM or Gravity.LEFT, left = 112, bottom = 68)
        add("↓", 2, Gravity.BOTTOM or Gravity.LEFT, left = 64, bottom = 20)
        add("←", 3, Gravity.BOTTOM or Gravity.LEFT, left = 16, bottom = 68)

        add("△", 4, Gravity.BOTTOM or Gravity.RIGHT, right = 64, bottom = 116)
        add("○", 5, Gravity.BOTTOM or Gravity.RIGHT, right = 16, bottom = 68)
        add("×", 6, Gravity.BOTTOM or Gravity.RIGHT, right = 64, bottom = 20)
        add("□", 7, Gravity.BOTTOM or Gravity.RIGHT, right = 112, bottom = 68)

        add("L1", 14, Gravity.TOP or Gravity.LEFT, left = 18, top = 14, size = 58)
        add("L2", 12, Gravity.TOP or Gravity.LEFT, left = 82, top = 14, size = 58)
        add("R2", 13, Gravity.TOP or Gravity.RIGHT, right = 82, top = 14, size = 58)
        add("R1", 15, Gravity.TOP or Gravity.RIGHT, right = 18, top = 14, size = 58)

        add("SEL", 8, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, left = -42, bottom = 18, size = 48)
        add("START", 11, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, left = 42, bottom = 18, size = 48)

        val exit = Button(this).apply {
            text = "EXIT"
            textSize = 10f
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = rounded(0x77000000, 14)
            setOnClickListener { finish() }
        }
        root.addView(exit, FrameLayout.LayoutParams(dp(62), dp(38), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(42) })
    }

    private fun prepareRuntime() {
        Thread({
            try {
                val dataRoot = File(filesDir, "ps2").apply { mkdirs() }
                val resourcesDir = File(dataRoot, "resources")
                if (!File(resourcesDir, "GameIndex.yaml").isFile) {
                    copyAssetTree("ARMSX2", resourcesDir)
                }
                val biosDir = Ps2BiosActivity.biosDir(this)
                if (Ps2BiosActivity.selectedBios(this) == null) error("BIOS PS2 belum siap")
                runOnUiThread { initializeCore(dataRoot, biosDir) }
            } catch (t: Throwable) {
                runOnUiThread {
                    Toast.makeText(this, "PS2 runtime gagal disiapkan: ${t.message}", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }, "ps2-prepare").start()
    }

    private fun copyAssetTree(assetPath: String, target: File) {
        val children = assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            assets.open(assetPath).use { input -> target.outputStream().use { input.copyTo(it) } }
            return
        }
        target.mkdirs()
        children.forEach { child -> copyAssetTree("$assetPath/$child", File(target, child)) }
    }

    private fun initializeCore(dataRoot: File, biosDir: File) {
        if (initialized || isFinishing) return
        try {
            NativeApp.attachContext(this)
            if (!NativeApp.isNativeReady()) {
                error("Native ARMSX2 gagal dimuat: ${NativeApp.nativeLoadError}")
            }

            status.text = "PS2 • initializing ARMSX2"
            NativeApp.initialize(dataRoot.absolutePath, biosDir.absolutePath, Build.VERSION.SDK_INT)
            NativeApp.renderVulkan()
            NativeApp.renderUpscalemultiplier(1.0f)
            NativeApp.setAffinityMode(7)
            if (Build.VERSION.SDK_INT >= 33) NativeApp.setAdpfEnabled(true)
            NativeApp.setAudioVolume(100)
            initialized = true

            // Important: never call ARMSX2 surface JNI before initialize().
            attachNativeSurfaceIfReady()
            status.text = "PS2 • ARMSX2 Vulkan • 1× native"
            maybeStartVm()
        } catch (t: Throwable) {
            Toast.makeText(this, "ARMSX2 init gagal: ${t.message ?: t.javaClass.simpleName}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun attachNativeSurfaceIfReady() {
        if (!initialized || !surfaceReady || nativeSurfaceAttached) return
        val holder = surface.holder
        val w = if (surfaceWidth > 0) surfaceWidth else surface.width
        val h = if (surfaceHeight > 0) surfaceHeight else surface.height
        if (!holder.surface.isValid || w <= 0 || h <= 0) return
        NativeApp.onNativeSurfaceCreated()
        NativeApp.onNativeSurfaceChanged(holder.surface, w, h)
        nativeSurfaceAttached = true
    }

    private fun maybeStartVm() {
        if (!initialized || !surfaceReady || !nativeSurfaceAttached || !vmStarted.compareAndSet(false, true)) return
        val hz = if (Build.VERSION.SDK_INT >= 30) display?.refreshRate ?: 60f else @Suppress("DEPRECATION") windowManager.defaultDisplay.refreshRate
        runCatching { NativeApp.setDisplayRefreshRate(hz) }
        status.text = "PS2 • booting game"
        vmThread = Thread({
            val result = runCatching { NativeApp.runVMThread(romPath) }
            val ok = result.getOrDefault(false)
            val error = result.exceptionOrNull()
            runOnUiThread {
                if (!isFinishing && !shuttingDown.get()) {
                    if (!ok) {
                        Toast.makeText(this, "PS2 gagal boot${error?.message?.let { ": $it" } ?: ". Cek BIOS/ROM."}", Toast.LENGTH_LONG).show()
                    }
                    finish()
                }
            }
        }, "armsx2-vm").also { it.priority = Thread.MAX_PRIORITY; it.start() }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        // Native surface attachment is deferred until ARMSX2 initialize() has completed.
        if (initialized) runCatching { attachNativeSurfaceIfReady() }
        maybeStartVm()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surfaceReady = true
        surfaceWidth = width
        surfaceHeight = height
        if (initialized) {
            if (!nativeSurfaceAttached) runCatching { attachNativeSurfaceIfReady() }
            else runCatching { NativeApp.onNativeSurfaceChanged(holder.surface, width, height) }
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

    override fun onPause() {
        super.onPause()
        if (!isFinishing && initialized && vmStarted.get()) runCatching { NativeApp.pause() }
        if (initialized) runCatching { NativeApp.flushShaderCache() }
    }

    override fun onResume() {
        super.onResume()
        if (initialized && vmStarted.get() && runCatching { NativeApp.isPaused() }.getOrDefault(false)) {
            runCatching { NativeApp.resume() }
        }
    }

    private fun shutdownCore() {
        if (!shuttingDown.compareAndSet(false, true)) return
        if (initialized) runCatching { NativeApp.resetKeyStatus() }
        if (initialized && vmStarted.get()) runCatching { NativeApp.shutdown() }
        vmThread?.let { thread ->
            if (thread.isAlive && thread !== Thread.currentThread()) runCatching { thread.join(2500) }
        }
        if (initialized && nativeSurfaceAttached) {
            runCatching { NativeApp.onNativeSurfaceDestroyed() }
            nativeSurfaceAttached = false
        }
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
