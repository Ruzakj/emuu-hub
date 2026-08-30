package com.ric.emuhub

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.ric.emuhub.core.NativeBridge
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

class GameActivity : Activity() {
    private var gameView: GameView? = null
    @Volatile private var fastForward = false
    private lateinit var stateFile: File
    private var analogX = 0
    private var analogY = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val rom = intent.getStringExtra("romPath") ?: run { finish(); return }
        val coreId = intent.getStringExtra("coreId") ?: "mgba"
        val romName = intent.getStringExtra("romName") ?: File(rom).name
        val coreFile = when (coreId) {
            "fceumm" -> "libfceumm_core.so"
            "snes9x" -> "libsnes9x_core.so"
            "pcsx" -> "libpcsx_rearmed_core.so"
            "ppsspp" -> "libppsspp_core.so"
            else -> "libmgba_core.so"
        }
        val coreLabel = when (coreId) {
            "fceumm" -> "FCEUmm"
            "snes9x" -> "Snes9x"
            "pcsx" -> "PCSX-ReARMed"
            "ppsspp" -> "PPSSPP"
            else -> "mGBA"
        }

        val systemRoot = File(filesDir, "system").apply { mkdirs() }
        if (coreId == "ppsspp") installPpssppAssets(systemRoot)

        val core = applicationInfo.nativeLibraryDir + "/$coreFile"
        val systemDir = systemRoot.absolutePath
        val saveDirFile = File(filesDir, "saves").apply { mkdirs() }
        val saveDir = saveDirFile.absolutePath
        stateFile = File(saveDirFile, "${coreId}_${safeStateKey(romName)}_slot0.state")

        if (!NativeBridge.init(core, systemDir, saveDir) || !NativeBridge.loadGame(rom)) {
            setContentView(TextView(this).apply {
                text = if (coreId == "ppsspp") {
                    "PPSSPP core gagal memuat game.\nJika build core berhasil tapi layar tetap hitam, host GPU OpenGL/Vulkan masih perlu ditambahkan."
                } else {
                    "$coreLabel core gagal memuat ROM."
                }
                gravity = Gravity.CENTER
                textSize = 18f
            })
            return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF09090B.toInt())
        }
        gameView = GameView().also { root.addView(it, LinearLayout.LayoutParams(-1, 0, 1f)) }
        root.addView(buildUtilityControls(), LinearLayout.LayoutParams(-1, -2))
        if (coreId == "pcsx" || coreId == "ppsspp") {
            root.addView(buildAnalogControls(), LinearLayout.LayoutParams(-1, -2))
        }
        root.addView(buildControls(coreId), LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
        gameView?.start()
    }

    private fun installPpssppAssets(systemRoot: File) {
        val target = File(systemRoot, "PPSSPP")
        val marker = File(target, ".emuhub_assets_v1")
        if (marker.exists()) return
        target.mkdirs()
        copyAssetTree("PPSSPP", target)
        runCatching { marker.writeText("1") }
    }

    private fun copyAssetTree(assetPath: String, target: File) {
        val entries = assets.list(assetPath) ?: return
        if (entries.isEmpty()) {
            target.parentFile?.mkdirs()
            assets.open(assetPath).use { input -> target.outputStream().use { input.copyTo(it) } }
            return
        }
        target.mkdirs()
        entries.forEach { name -> copyAssetTree("$assetPath/$name", File(target, name)) }
    }

    private fun safeStateKey(name: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(name.toByteArray())
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    private fun buildUtilityControls(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(8, 4, 8, 0)
        }

        row.addView(Button(this).apply {
            text = "SAVE"
            setOnClickListener {
                val ok = NativeBridge.saveState(stateFile.absolutePath)
                Toast.makeText(this@GameActivity, if (ok) "State tersimpan" else "Save state gagal", Toast.LENGTH_SHORT).show()
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))

        row.addView(Button(this).apply {
            text = "LOAD"
            setOnClickListener {
                val ok = stateFile.exists() && NativeBridge.loadState(stateFile.absolutePath)
                Toast.makeText(this@GameActivity, if (ok) "State dimuat" else "Belum ada state / load gagal", Toast.LENGTH_SHORT).show()
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))

        row.addView(Button(this).apply {
            text = "FAST 2×"
            setOnClickListener {
                fastForward = !fastForward
                text = if (fastForward) "FAST ON" else "FAST 2×"
                gameView?.onFastForwardChanged(fastForward)
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))

        row.addView(Button(this).apply {
            text = "RESET"
            setOnClickListener { NativeBridge.reset() }
        }, LinearLayout.LayoutParams(0, -2, 1f))

        return row
    }

    private fun buildAnalogControls(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(8, 2, 8, 2)
        }
        listOf("A←" to Pair(-32767, 0), "A↑" to Pair(0, -32767), "A↓" to Pair(0, 32767), "A→" to Pair(32767, 0)).forEach { (label, axis) ->
            row.addView(Button(this).apply {
                text = label
                minWidth = 0
                setOnTouchListener { _, e ->
                    when (e.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            if (axis.first != 0) analogX = axis.first
                            if (axis.second != 0) analogY = axis.second
                            NativeBridge.setAnalog(analogX, analogY)
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            if (axis.first != 0) analogX = 0
                            if (axis.second != 0) analogY = 0
                            NativeBridge.setAnalog(analogX, analogY)
                        }
                    }
                    true
                }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        return row
    }

    private fun buildControls(coreId: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(8, 8, 8, 12)
        }
        val controls = mutableListOf(
            "←" to 6, "↑" to 4, "↓" to 5, "→" to 7,
            "L" to 10, "SELECT" to 2, "START" to 3, "R" to 11,
            "B" to 0, "A" to 8
        )
        if (coreId == "snes9x" || coreId == "pcsx" || coreId == "ppsspp") {
            controls.add("Y" to 1)
            controls.add("X" to 9)
        }
        if (coreId == "pcsx") {
            controls.add("L2" to 12)
            controls.add("R2" to 13)
        }
        controls.forEach { (label, id) ->
            val b = Button(this).apply {
                text = label
                minWidth = 0
                setOnTouchListener { _, e ->
                    when (e.actionMasked) {
                        MotionEvent.ACTION_DOWN -> NativeBridge.setButton(id, true)
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> NativeBridge.setButton(id, false)
                    }
                    true
                }
            }
            row.addView(b, LinearLayout.LayoutParams(0, -2, if (label.length > 2) 1.5f else 1f))
        }
        return row
    }

    override fun onDestroy() {
        gameView?.stop()
        NativeBridge.setAnalog(0, 0)
        NativeBridge.unload()
        super.onDestroy()
    }

    inner class GameView : View(this@GameActivity), Runnable {
        private val running = AtomicBoolean(false)
        private val widthPx = NativeBridge.getWidth().coerceAtLeast(1)
        private val heightPx = NativeBridge.getHeight().coerceAtLeast(1)
        private val pixels = IntArray(widthPx * heightPx)
        private val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private var thread: Thread? = null
        private val audioScratch = ShortArray(8192)
        private var audioTrack: AudioTrack? = null

        fun start() {
            val rate = NativeBridge.getSampleRate().coerceAtLeast(8000)
            val minBufferBytes = AudioTrack.getMinBufferSize(
                rate,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(4096)
            val targetBufferBytes = maxOf(minBufferBytes * 2, rate * 4 / 12)

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(targetBufferBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build().also { it.play() }

            if (running.compareAndSet(false, true)) {
                thread = Thread(this, "EmuFrame").also { it.start() }
            }
        }

        fun onFastForwardChanged(enabled: Boolean) {
            if (enabled) {
                try { audioTrack?.pause() } catch (_: Exception) {}
                try { audioTrack?.flush() } catch (_: Exception) {}
            } else {
                try { audioTrack?.flush() } catch (_: Exception) {}
                try { audioTrack?.play() } catch (_: Exception) {}
            }
        }

        fun stop() {
            running.set(false)
            thread?.interrupt()
            try { thread?.join(500) } catch (_: InterruptedException) {}
            try { audioTrack?.pause() } catch (_: Exception) {}
            try { audioTrack?.flush() } catch (_: Exception) {}
            try { audioTrack?.stop() } catch (_: Exception) {}
            audioTrack?.release()
            audioTrack = null
        }

        override fun run() {
            while (running.get()) {
                val n = NativeBridge.runFrame(pixels)
                if (n > 0) {
                    bitmap.setPixels(pixels, 0, widthPx, 0, 0, widthPx, heightPx)
                    postInvalidate()
                }

                if (fastForward) {
                    while (NativeBridge.readAudio(audioScratch) > 0) {}
                    try { Thread.sleep(8) } catch (_: InterruptedException) { break }
                } else {
                    var audioCount = NativeBridge.readAudio(audioScratch)
                    while (audioCount > 0 && running.get()) {
                        var offset = 0
                        while (offset < audioCount && running.get()) {
                            val written = audioTrack?.write(
                                audioScratch,
                                offset,
                                audioCount - offset,
                                AudioTrack.WRITE_BLOCKING
                            ) ?: -1
                            if (written > 0) offset += written else break
                        }
                        audioCount = NativeBridge.readAudio(audioScratch)
                    }
                }
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val scale = minOf(width.toFloat() / widthPx, height.toFloat() / heightPx)
            val dw = widthPx * scale
            val dh = heightPx * scale
            val left = (width - dw) / 2f
            val top = (height - dh) / 2f
            canvas.drawBitmap(bitmap, null, android.graphics.RectF(left, top, left + dw, top + dh), paint)
        }
    }
}
