package com.ric.emuhub

import android.app.Activity
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ru.playsoftware.j2meloader.config.Config
import ru.playsoftware.j2meloader.config.ProfileModel
import ru.playsoftware.j2meloader.config.ProfilesManager
import java.io.File
import java.util.concurrent.Executors

class J2meLibraryActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var recycler: RecyclerView
    private lateinit var status: TextView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun rounded(color: Int, r: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(r).toFloat(); if (stroke != null) setStroke(dp(1), stroke)
    }
    private fun tv(t: String, sp: Float, c: Int, b: Boolean = false) = TextView(this).apply {
        text = t; textSize = sp; setTextColor(c); includeFontPadding = false
        if (b) setTypeface(typeface, Typeface.BOLD)
    }

    private fun trace(game: String, stage: String, active: Boolean = true) {
        getSharedPreferences("j2me_runtime_trace", MODE_PRIVATE).edit()
            .putString("game", game)
            .putString("stage", stage)
            .putLong("stage_time", System.currentTimeMillis())
            .putBoolean("active", active)
            .commit()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF000000.toInt(); window.navigationBarColor = 0xFF000000.toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFF000000.toInt()); setPadding(dp(18), dp(16), dp(18), dp(12))
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(tv("‹", 36f, 0xFFFFFFFF.toInt(), true).apply {
            gravity = Gravity.CENTER; setPadding(dp(6), 0, dp(14), 0); setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(48), dp(52)))
        header.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(tv("JAVA / J2ME", 23f, 0xFFFFFFFF.toInt(), true))
            addView(tv("JL-Mod internal runtime • direct", 10.5f, 0xFF777777.toInt()))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(header)

        status = tv("Membaca library…", 11f, 0xFF8A8A8A.toInt())
        root.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8); bottomMargin = dp(10)
        })

        recycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@J2meLibraryActivity)
            setHasFixedSize(true)
            itemAnimator = null
            overScrollMode = RecyclerView.OVER_SCROLL_NEVER
        }
        root.addView(recycler, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        scan()
    }

    private fun scan() {
        worker.execute {
            @Suppress("DEPRECATION")
            val converted = File(File(Environment.getExternalStorageDirectory(), "Java"), "converted")
            val games = converted.listFiles()
                ?.asSequence()
                ?.filter { it.isDirectory && File(it, "converted.zip").isFile }
                ?.sortedBy { it.name.lowercase() }
                ?.toList().orEmpty()
            runOnUiThread {
                status.text = "${games.size} game • ${converted.absolutePath}"
                recycler.adapter = GameAdapter(games)
            }
        }
    }

    private fun launchGame(dir: File) {
        trace(dir.name, "tap-game")
        worker.execute {
            try {
                trace(dir.name, "validate-files")
                val dex = File(dir, "converted.zip")
                val res = File(dir, "res.jar")
                val manifest = File(dir, "converted.dex.conf")
                if (!dex.isFile || !res.isFile || !manifest.isFile) {
                    trace(dir.name, "invalid-files", false)
                    runOnUiThread { Toast.makeText(this, "File J2ME belum lengkap: ${dir.name}", Toast.LENGTH_LONG).show() }
                    return@execute
                }

                @Suppress("DEPRECATION")
                val javaRoot = File(Environment.getExternalStorageDirectory(), "Java")
                val configDir = File(File(javaRoot, "configs"), dir.name)
                val configFile = File(configDir, "config.json")
                trace(dir.name, "prepare-config")
                if (!configFile.isFile) {
                    if (!configDir.exists()) configDir.mkdirs()
                    val profile = ProfileModel(configDir)
                    if (!ProfilesManager.saveConfig(profile)) {
                        trace(dir.name, "config-save-failed", false)
                        runOnUiThread { Toast.makeText(this, "Gagal membuat config J2ME", Toast.LENGTH_LONG).show() }
                        return@execute
                    }
                }
                trace(dir.name, "before-config-startApp")
                runOnUiThread {
                    runCatching {
                        trace(dir.name, "startApp-call")
                        Config.startApp(this, dir.name, dir.absolutePath)
                        trace(dir.name, "activity-started")
                    }.onFailure {
                        trace(dir.name, "startApp-exception")
                        Toast.makeText(this, "J2ME gagal start: ${it.javaClass.simpleName}: ${it.message}", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (t: Throwable) {
                trace(dir.name, "launcher-exception")
                runOnUiThread { Toast.makeText(this, "J2ME error: ${t.javaClass.simpleName}: ${t.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private inner class GameAdapter(private val games: List<File>) : RecyclerView.Adapter<GameVH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GameVH {
            val row = LinearLayout(this@J2meLibraryActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(10), dp(12), dp(10)); background = rounded(0xFF080808.toInt(), 16, 0xFF1B1B1B.toInt())
                isClickable = true; isFocusable = true
            }
            val icon = ImageView(this@J2meLibraryActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = rounded(0xFF141414.toInt(), 11) }
            row.addView(icon, LinearLayout.LayoutParams(dp(58), dp(58)))
            val textBox = LinearLayout(this@J2meLibraryActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(13), 0, 0, 0) }
            val title = tv("", 14f, 0xFFF4F4F4.toInt(), true).apply { maxLines = 2 }
            val sub = tv("J2ME • converted.zip", 10f, 0xFF737373.toInt())
            textBox.addView(title)
            textBox.addView(sub, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(5) })
            row.addView(textBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            return GameVH(row, icon, title)
        }
        override fun getItemCount() = games.size
        override fun onBindViewHolder(holder: GameVH, position: Int) {
            val dir = games[position]
            holder.title.text = dir.name
            holder.icon.setImageDrawable(null)
            val iconFile = File(dir, "icon.png")
            if (iconFile.isFile) runCatching { BitmapFactory.decodeFile(iconFile.absolutePath) }.getOrNull()?.let(holder.icon::setImageBitmap)
            holder.itemView.setOnClickListener { launchGame(dir) }
        }
    }

    private class GameVH(view: LinearLayout, val icon: ImageView, val title: TextView) : RecyclerView.ViewHolder(view)

    override fun onDestroy() { worker.shutdownNow(); super.onDestroy() }
}
