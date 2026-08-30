package com.ric.emuhub

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.ric.emuhub.core.NativeBridge
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class GameActivity : Activity() {
    private var gameView: GameView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val rom = intent.getStringExtra("romPath") ?: run { finish(); return }
        val core = applicationInfo.nativeLibraryDir + "/libmgba_core.so"
        val systemDir = File(filesDir, "system").apply { mkdirs() }.absolutePath
        val saveDir = File(filesDir, "saves").apply { mkdirs() }.absolutePath

        if (!NativeBridge.init(core, systemDir, saveDir) || !NativeBridge.loadGame(rom)) {
            setContentView(TextView(this).apply {
                text = "mGBA core gagal memuat ROM.\nPastikan file adalah .gb, .gbc, atau .gba."
                gravity = Gravity.CENTER
                textSize = 18f
            })
            return
        }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFF09090B.toInt()) }
        gameView = GameView().also { root.addView(it, LinearLayout.LayoutParams(-1, 0, 1f)) }
        root.addView(buildControls(), LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
        gameView?.start()
    }

    private fun buildControls(): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(8, 8, 8, 12) }
        listOf(
            "←" to 6, "↑" to 4, "↓" to 5, "→" to 7,
            "L" to 10, "SELECT" to 2, "START" to 3, "R" to 11,
            "B" to 0, "A" to 8
        ).forEach { (label, id) ->
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
        NativeBridge.unload()
        super.onDestroy()
    }

    inner class GameView : View(this), Runnable {
        private val running = AtomicBoolean(false)
        private val widthPx = NativeBridge.getWidth().coerceAtLeast(1)
        private val heightPx = NativeBridge.getHeight().coerceAtLeast(1)
        private val pixels = IntArray(widthPx * heightPx)
        private val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private var thread: Thread? = null

        fun start() { if (running.compareAndSet(false, true)) thread = Thread(this, "EmuFrame").also { it.start() } }
        fun stop() { running.set(false); try { thread?.join(500) } catch (_: InterruptedException) {} }

        override fun run() {
            while (running.get()) {
                val n = NativeBridge.runFrame(pixels)
                if (n > 0) {
                    bitmap.setPixels(pixels, 0, widthPx, 0, 0, widthPx, heightPx)
                    postInvalidate()
                }
                try { Thread.sleep(16) } catch (_: InterruptedException) { break }
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val scale = minOf(width.toFloat() / widthPx, height.toFloat() / heightPx)
            val dw = widthPx * scale; val dh = heightPx * scale
            val left = (width - dw) / 2f; val top = (height - dh) / 2f
            canvas.drawBitmap(bitmap, null, android.graphics.RectF(left, top, left + dw, top + dh), paint)
        }
    }
}
