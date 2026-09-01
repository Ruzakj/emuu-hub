package com.ric.emuhub

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
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
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/** ARMSX2 host tuned specifically for Emu Hub's Snapdragon 6 Gen 1 / Adreno 710 target. */
class Ps2GameActivity : Activity(), SurfaceHolder.Callback {
    companion object {
        const val EXTRA_BIOS_ONLY = "biosOnly"
        private const val TRACE_PREFS = "ps2_runtime_trace"
        private const val TRACE_STAGE = "stage"
        private const val TRACE_ACTIVE = "active"
        private const val PS2_UPSCALE = 1.5f

        private const val PAD_L_UP = 110
        private const val PAD_L_RIGHT = 111
        private const val PAD_L_DOWN = 112
        private const val PAD_L_LEFT = 113
        private const val PAD_R_UP = 120
        private const val PAD_R_RIGHT = 121
        private const val PAD_R_DOWN = 122
        private const val PAD_R_LEFT = 123
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
            .putString(TRACE_STAGE, stage)
            .putBoolean(TRACE_ACTIVE, active)
            .putString("last_crash_stage", stage)
            .putLong("last_crash_time", System.currentTimeMillis())
            .commit()
        runCatching {
            val dir = File(filesDir, "ps2").apply { mkdirs() }
            File(dir, "last_native_stage.txt").writeText("$stage\n${System.currentTimeMillis()}\n")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        biosOnly = intent.getBooleanExtra(EXTRA_BIOS_ONLY, false)
        trace(if (biosOnly) "bios-only-activity-created" else "activity-created")
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) runCatching { window.setSustainedPerformanceMode(true) }
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE

        romPath = intent.getStringExtra("romPath").orEmpty()
        if (!biosOnly && (romPath.isBlank() || !File(romPath).isFile)) {
            trace("rom-missing", false); Toast.makeText(this, "PS2 ROM tidak ditemukan.", Toast.LENGTH_LONG).show(); finish(); return
        }
        if (Ps2BiosActivity.selectedBios(this) == null) {
            trace("bios-missing", false); Toast.makeText(this, "Pilih BIOS PS2 dulu.", Toast.LENGTH_LONG).show(); finish(); return
        }
        buildGameUi(); prepareRuntime()
    }

    private fun rounded(alpha: Int = 0x77): GradientDrawable = GradientDrawable().apply {
        setColor(Color.argb(alpha, 18, 18, 18)); cornerRadius = dp(18).toFloat(); setStroke(dp(1), Color.argb(110, 255, 255, 255))
    }

    private fun control(text: String, keyCode: Int, size: Int = 54): Button = Button(this).apply {
        this.text = text; textSize = 16f; isAllCaps = false; setTextColor(Color.WHITE); background = rounded()
        setPadding(0, 0, 0, 0); minWidth = 0; minHeight = 0
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { v.isPressed = true; sendPad(keyCode, true) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.isPressed = false; sendPad(keyCode, false) }
            }
            true
        }
        layoutParams = FrameLayout.LayoutParams(dp(size), dp(size))
    }

    private fun addControl(root: FrameLayout, button: View, gravity: Int, left: Int = 0, top: Int = 0, right: Int = 0, bottom: Int = 0, w: Int = 54, h: Int = 54) {
        root.addView(button, FrameLayout.LayoutParams(dp(w), dp(h), gravity).apply {
            leftMargin = dp(left); topMargin = dp(top); rightMargin = dp(right); bottomMargin = dp(bottom)
        })
    }

    private inner class AnalogStickView : View(this@Ps2GameActivity) {
        private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(105, 24, 24, 24); style = Paint.Style.FILL }
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 255, 255, 255); style = Paint.Style.STROKE; strokeWidth = dp(1).toFloat() }
        private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(175, 150, 150, 150); style = Paint.Style.FILL }
        private var knobX = 0f
        private var knobY = 0f

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f; val cy = height / 2f
            val outer = min(width, height) * 0.46f
            val knob = outer * 0.42f
            canvas.drawCircle(cx, cy, outer, basePaint)
            canvas.drawCircle(cx, cy, outer, ringPaint)
            canvas.drawCircle(cx + knobX, cy + knobY, knob, knobPaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    val cx = width / 2f; val cy = height / 2f
                    var dx = event.x - cx; var dy = event.y - cy
                    val limit = min(width, height) * 0.36f
                    val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
                    if (dist > limit && dist > 0f) { val scale = limit / dist; dx *= scale; dy *= scale }
                    knobX = dx; knobY = dy
                    val nx = (dx / limit).coerceIn(-1f, 1f)
                    val ny = (dy / limit).coerceIn(-1f, 1f)
                    sendAnalogPair(nx, PAD_L_LEFT, PAD_L_RIGHT)
                    sendAnalogPair(ny, PAD_L_UP, PAD_L_DOWN)
                    invalidate()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    knobX = 0f; knobY = 0f
                    sendPad(PAD_L_LEFT, false); sendPad(PAD_L_RIGHT, false)
                    sendPad(PAD_L_UP, false); sendPad(PAD_L_DOWN, false)
                    invalidate()
                }
            }
            return true
        }
    }

    private fun buildGameUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        surface = SurfaceView(this).also { it.holder.addCallback(this) }
        root.addView(surface, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        status = TextView(this).apply {
            text = if (biosOnly) "PS2 • BIOS" else "PS2 • Z9X PERFORMANCE"
            textSize = 10f; setTextColor(0xFFD0D0D0.toInt()); setBackgroundColor(0x77000000); setPadding(dp(9), dp(5), dp(9), dp(5))
        }
        root.addView(status, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(8) })
        addControl(root, Button(this).apply { text = "EXIT"; textSize = 10f; isAllCaps = false; setTextColor(Color.WHITE); background = rounded(); setOnClickListener { finish() } }, Gravity.TOP or Gravity.END, top = 8, right = 10, w = 66, h = 38)

        if (!biosOnly) {
            addControl(root, control("L2", KeyEvent.KEYCODE_BUTTON_L2, 48), Gravity.TOP or Gravity.START, left = 10, top = 8, w = 48, h = 40)
            addControl(root, control("L1", KeyEvent.KEYCODE_BUTTON_L1, 48), Gravity.TOP or Gravity.START, left = 64, top = 8, w = 48, h = 40)
            addControl(root, control("R1", KeyEvent.KEYCODE_BUTTON_R1, 48), Gravity.TOP or Gravity.END, right = 118, top = 8, w = 48, h = 40)
            addControl(root, control("R2", KeyEvent.KEYCODE_BUTTON_R2, 48), Gravity.TOP or Gravity.END, right = 64, top = 8, w = 48, h = 40)
            addControl(root, control("▲", KeyEvent.KEYCODE_DPAD_UP), Gravity.BOTTOM or Gravity.START, left = 72, bottom = 126)
            addControl(root, control("▼", KeyEvent.KEYCODE_DPAD_DOWN), Gravity.BOTTOM or Gravity.START, left = 72, bottom = 18)
            addControl(root, control("◀", KeyEvent.KEYCODE_DPAD_LEFT), Gravity.BOTTOM or Gravity.START, left = 18, bottom = 72)
            addControl(root, control("▶", KeyEvent.KEYCODE_DPAD_RIGHT), Gravity.BOTTOM or Gravity.START, left = 126, bottom = 72)
            addControl(root, AnalogStickView(), Gravity.BOTTOM or Gravity.START, left = 205, bottom = 28, w = 132, h = 132)
            addControl(root, control("△", KeyEvent.KEYCODE_BUTTON_Y), Gravity.BOTTOM or Gravity.END, right = 72, bottom = 126)
            addControl(root, control("✕", KeyEvent.KEYCODE_BUTTON_A), Gravity.BOTTOM or Gravity.END, right = 72, bottom = 18)
            addControl(root, control("□", KeyEvent.KEYCODE_BUTTON_X), Gravity.BOTTOM or Gravity.END, right = 126, bottom = 72)
            addControl(root, control("○", KeyEvent.KEYCODE_BUTTON_B), Gravity.BOTTOM or Gravity.END, right = 18, bottom = 72)
            addControl(root, control("SELECT", KeyEvent.KEYCODE_BUTTON_SELECT, 58), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, left = -62, bottom = 22, w = 76, h = 36)
            addControl(root, control("START", KeyEvent.KEYCODE_BUTTON_START, 58), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, right = -62, bottom = 22, w = 76, h = 36)
        }
        setContentView(root)
    }

    private fun sendPad(keyCode: Int, pressed: Boolean, analogForce: Int = 0) {
        if (!initialized) return
        val force = if (pressed && keyCode >= 110) { if (analogForce > 0) analogForce else (90f * 32766f / 100f).toInt() } else 0
        runCatching { NativeApp.setPadButton(keyCode, force, pressed) }
    }

    private fun sendAnalogPair(value: Float, negativeKey: Int, positiveKey: Int) {
        val dead = 0.10f
        val magnitude = min(1f, abs(value))
        val force = (magnitude * 32766f).toInt()
        if (value < -dead) { sendPad(negativeKey, true, force); sendPad(positiveKey, false) }
        else if (value > dead) { sendPad(positiveKey, true, force); sendPad(negativeKey, false) }
        else { sendPad(negativeKey, false); sendPad(positiveKey, false) }
    }

    private fun prepareRuntime() {
        Thread({ try {
            trace("preparing-resources")
            val dataRoot = StoragePaths.ps2Root(this); val resourcesDir = File(dataRoot, "resources")
            resourcesDir.deleteRecursively(); copyAssetTree("ARMSX2", resourcesDir)
            val biosDir = Ps2BiosActivity.biosDir(this)
            trace("resources-ready"); runOnUiThread { initializeCore(dataRoot, biosDir) }
        } catch (t: Throwable) { trace("prepare-error:${t.javaClass.simpleName}", false); runOnUiThread { Toast.makeText(this, "PS2 runtime gagal: ${t.message}", Toast.LENGTH_LONG).show(); finish() } } }, "ps2-prepare").start()
    }

    private fun copyAssetTree(assetPath: String, target: File) { val children = assets.list(assetPath).orEmpty(); if (children.isEmpty()) { target.parentFile?.mkdirs(); assets.open(assetPath).use { i -> target.outputStream().use { i.copyTo(it) } }; return }; target.mkdirs(); children.forEach { copyAssetTree("$assetPath/$it", File(target, it)) } }

    private fun initializeCore(dataRoot: File, biosDir: File) { if (initialized || isFinishing) return; try {
        status.text = "PS2 • native load"; trace("before-native-load")
        if (!NativeApp.loadNative(this)) error("Native ARMSX2 gagal dimuat: ${NativeApp.nativeLoadError}")
        trace("after-native-load"); trace("sdl-setup-skipped")
        status.text = "PS2 • initialize"; trace(if (biosOnly) "bios-only-before-initialize" else "before-initialize")
        NativeApp.initialize(dataRoot.absolutePath, biosDir.absolutePath, Build.VERSION.SDK_INT)
        trace(if (biosOnly) "bios-only-after-initialize" else "after-initialize")

        if (!biosOnly) {
            trace("before-z9x-performance-profile")
            runCatching { NativeApp.setAffinityMode(7) }
            runCatching { NativeApp.renderVulkan() }
            runCatching { NativeApp.renderUpscalemultiplier(PS2_UPSCALE) }
            if (Build.VERSION.SDK_INT >= 33) runCatching { NativeApp.setAdpfEnabled(true) }
            runCatching { NativeApp.setSetting("EmuCore/Speedhacks", "vuThread", "bool", "true") }
            runCatching { NativeApp.setSetting("EmuCore/Speedhacks", "WaitLoop", "bool", "true") }
            runCatching { NativeApp.setSetting("EmuCore/Speedhacks", "IntcStat", "bool", "true") }
            runCatching { NativeApp.setSetting("EmuCore/Speedhacks", "vuFlagHack", "bool", "true") }
            runCatching { NativeApp.setSetting("EmuCore/Speedhacks", "EECycleRate", "int", "0") }
            runCatching { NativeApp.setSetting("EmuCore/Speedhacks", "EECycleSkip", "int", "0") }
            runCatching { NativeApp.commitSettings() }
            runCatching { NativeApp.setAudioVolume(100) }
            @Suppress("DEPRECATION") val hz = windowManager.defaultDisplay.refreshRate
            if (hz > 0f) runCatching { NativeApp.setDisplayRefreshRate(hz) }
            runCatching { NativeApp.resetKeyStatus() }
            trace("z9x-performance-profile-ready")
        }
        initialized = true; attachNativeSurfaceIfReady(); maybeStartVm()
    } catch (t: Throwable) { trace("init-error:${t.javaClass.simpleName}", false); Toast.makeText(this, "ARMSX2 init gagal: ${t.message ?: t.javaClass.simpleName}", Toast.LENGTH_LONG).show(); finish() } }

    private fun attachNativeSurfaceIfReady() { if (!initialized || !surfaceReady || nativeSurfaceAttached) return; val h = surface.holder; val w = if (surfaceWidth > 0) surfaceWidth else surface.width; val ht = if (surfaceHeight > 0) surfaceHeight else surface.height; if (!h.surface.isValid || w <= 0 || ht <= 0) return; status.text = "PS2 • attach surface"; trace("before-surface-created"); NativeApp.onNativeSurfaceCreated(); trace("after-surface-created"); trace("before-surface-changed"); NativeApp.onNativeSurfaceChanged(h.surface, w, ht); trace("after-surface-changed"); nativeSurfaceAttached = true }

    private fun maybeStartVm() { if (!initialized || !surfaceReady || !nativeSurfaceAttached || !vmStarted.compareAndSet(false, true)) return
        status.text = if (biosOnly) "PS2 • boot BIOS" else "PS2 • Vulkan 1.5x • PERF CORES"; trace(if (biosOnly) "bios-only-before-run-vm" else "before-run-vm")
        val bootPath = if (biosOnly) "" else romPath
        vmThread = Thread({
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY) }
            val result = runCatching { NativeApp.runVMThread(bootPath) }
            val ok = result.getOrDefault(false)
            trace(if (ok) "vm-returned-ok" else "vm-returned-false", false)
            runOnUiThread { if (!isFinishing && !shuttingDown.get()) { if (!ok) Toast.makeText(this, if (biosOnly) "BIOS-only boot gagal." else "PS2 gagal boot.", Toast.LENGTH_LONG).show(); finish() } }
        }, "armsx2-vm").also { it.priority = Thread.MAX_PRIORITY; it.start() }
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (initialized && (event.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK && event.action == MotionEvent.ACTION_MOVE) {
            sendAnalogPair(event.getAxisValue(MotionEvent.AXIS_X), PAD_L_LEFT, PAD_L_RIGHT)
            sendAnalogPair(event.getAxisValue(MotionEvent.AXIS_Y), PAD_L_UP, PAD_L_DOWN)
            sendAnalogPair(event.getAxisValue(MotionEvent.AXIS_Z), PAD_R_LEFT, PAD_R_RIGHT)
            sendAnalogPair(event.getAxisValue(MotionEvent.AXIS_RZ), PAD_R_UP, PAD_R_DOWN)
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (initialized && event.repeatCount == 0 && ((event.source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD || (event.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK)) { sendPad(keyCode, true); return true }
        if (keyCode == KeyEvent.KEYCODE_BACK) { finish(); return true }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (initialized && ((event.source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD || (event.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK)) { sendPad(keyCode, false); return true }
        return super.onKeyUp(keyCode, event)
    }

    override fun surfaceCreated(holder: SurfaceHolder) { surfaceReady = true; if (initialized) runCatching { attachNativeSurfaceIfReady() }; maybeStartVm() }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { surfaceReady = true; surfaceWidth = width; surfaceHeight = height; if (initialized) { if (!nativeSurfaceAttached) runCatching { attachNativeSurfaceIfReady() } else runCatching { NativeApp.onNativeSurfaceChanged(holder.surface, width, height) } }; maybeStartVm() }
    override fun surfaceDestroyed(holder: SurfaceHolder) { surfaceReady = false; if (initialized && nativeSurfaceAttached) { runCatching { NativeApp.onNativeSurfaceDestroyed() }; nativeSurfaceAttached = false } }
    override fun onPause() { if (initialized && vmStarted.get() && !shuttingDown.get()) { runCatching { NativeApp.pause() }; runCatching { NativeApp.flushShaderCache() } }; super.onPause() }
    override fun onResume() { super.onResume(); if (initialized && vmStarted.get() && !shuttingDown.get()) runCatching { NativeApp.resume() } }

    private fun shutdownCore() { if (!shuttingDown.compareAndSet(false, true)) return; if (initialized) runCatching { NativeApp.resetKeyStatus() }; if (initialized && vmStarted.get()) runCatching { NativeApp.shutdown() }; vmThread?.let { if (it.isAlive && it !== Thread.currentThread()) runCatching { it.join(1500) } }; if (initialized && nativeSurfaceAttached) { runCatching { NativeApp.onNativeSurfaceDestroyed() }; nativeSurfaceAttached = false }; if (!vmStarted.get()) trace("closed-before-vm", false) }
    override fun finish() { shutdownCore(); super.finish() }
    override fun onDestroy() { shutdownCore(); super.onDestroy() }
    @Deprecated("Framework compatibility") override fun onBackPressed() { finish() }
}
