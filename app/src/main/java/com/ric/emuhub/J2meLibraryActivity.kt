package com.ric.emuhub

import android.app.Activity
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import ru.playsoftware.j2meloader.config.Config
import java.io.File
import java.util.concurrent.Executors

class J2meLibraryActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var list: LinearLayout
    private lateinit var status: TextView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun rounded(color: Int, r: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(r).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }
    private fun tv(t: String, sp: Float, c: Int, b: Boolean = false) = TextView(this).apply {
        text = t
        textSize = sp
        setTextColor(c)
        includeFontPadding = false
        if (b) setTypeface(typeface, Typeface.BOLD)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF000000.toInt()
        window.navigationBarColor = 0xFF000000.toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF000000.toInt())
            setPadding(dp(18), dp(16), dp(18), dp(12))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(tv("‹", 36f, 0xFFFFFFFF.toInt(), true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(6), 0, dp(14), 0)
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(48), dp(52)))
        header.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(tv("JAVA / J2ME", 23f, 0xFFFFFFFF.toInt(), true))
            addView(tv("JL-Mod internal runtime", 10.5f, 0xFF777777.toInt()))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(header)

        status = tv("Scanning /storage/emulated/0/Java/converted…", 11f, 0xFF8A8A8A.toInt())
        root.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
            bottomMargin = dp(10)
        })

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        scan()
    }

    private fun scan() {
        worker.execute {
            @Suppress("DEPRECATION")
            val javaRoot = File(Environment.getExternalStorageDirectory(), "Java")
            val converted = File(javaRoot, "converted")
            val games = converted.listFiles()
                ?.filter { it.isDirectory && File(it, "converted.zip").isFile && File(it, "res.jar").isFile }
                ?.sortedBy { it.name.lowercase() }
                .orEmpty()
            runOnUiThread {
                list.removeAllViews()
                status.text = "${games.size} game • ${converted.absolutePath}"
                if (games.isEmpty()) {
                    list.addView(tv("Folder converted belum ditemukan atau belum bisa dibaca.", 13f, 0xFFAAAAAA.toInt()).apply {
                        setPadding(dp(12), dp(24), dp(12), dp(24))
                    })
                    return@runOnUiThread
                }
                games.forEach(::addGame)
            }
        }
    }

    private fun addGame(dir: File) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(10), dp(12), dp(10))
            background = rounded(0xFF080808.toInt(), 16, 0xFF1B1B1B.toInt())
            isClickable = true
            isFocusable = true
        }
        val icon = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(0xFF141414.toInt(), 11)
        }
        File(dir, "icon.png").takeIf(File::isFile)?.let { file ->
            runCatching { icon.setImageBitmap(BitmapFactory.decodeFile(file.absolutePath)) }
        }
        row.addView(icon, LinearLayout.LayoutParams(dp(58), dp(58)))
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), 0, 0, 0)
            addView(tv(dir.name, 14f, 0xFFF4F4F4.toInt(), true).apply { maxLines = 2 })
            addView(tv("J2ME • converted.zip", 10f, 0xFF737373.toInt()), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(5) })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.setOnClickListener {
            runCatching { Config.startApp(this, dir.name, dir.absolutePath) }
                .onFailure { Toast.makeText(this, "J2ME gagal start: ${it.message}", Toast.LENGTH_LONG).show() }
        }
        list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }
}
