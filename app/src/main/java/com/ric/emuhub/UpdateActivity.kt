package com.ric.emuhub

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class UpdateActivity : Activity() {
    companion object {
        private const val LATEST_RELEASE = "https://api.github.com/repos/Ruzakj/emuu-hub/releases/latest"
        private const val APK_MIME = "application/vnd.android.package-archive"
    }

    private val io = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var action: Button
    private lateinit var progress: ProgressBar
    private var pendingApk: File? = null
    private var downloadId: Long = -1L

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) != downloadId) return
            val apk = pendingApk ?: return
            progress.visibility = ProgressBar.GONE
            if (apk.exists() && apk.length() > 0L) installApk(apk) else {
                status.text = "Download gagal"
                action.isEnabled = true
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(downloadReceiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(downloadReceiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        checkUpdate()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(downloadReceiver) }
        io.shutdownNow()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        val apk = pendingApk
        if (apk != null && apk.exists() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && packageManager.canRequestPackageInstalls()) installApk(apk)
    }

    private fun render() {
        val pad = (22 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(0xFF05070A.toInt())
        }
        root.addView(TextView(this).apply {
            text = "SYSTEM UPDATE"
            textSize = 12f
            setTextColor(0xFF8E98A8.toInt())
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.12f
        })
        root.addView(TextView(this).apply {
            text = "Keep Emu Hub current"
            textSize = 27f
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = pad / 2 })
        root.addView(TextView(this).apply {
            text = "Installed: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
            textSize = 12f
            setTextColor(0xFF7D8795.toInt())
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = pad / 3 })
        status = TextView(this).apply {
            text = "Checking GitHub Release…"
            textSize = 14f
            setTextColor(0xFFF0F3F7.toInt())
        }
        root.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = pad })
        progress = ProgressBar(this).apply { isIndeterminate = true }
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = pad / 2; gravity = Gravity.CENTER_HORIZONTAL })
        action = Button(this).apply {
            text = "CHECK AGAIN"
            isEnabled = false
            setOnClickListener { checkUpdate() }
        }
        root.addView(action, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (52 * resources.displayMetrics.density).toInt()).apply { topMargin = pad })
        setContentView(root)
    }

    private fun checkUpdate() {
        action.isEnabled = false
        progress.visibility = ProgressBar.VISIBLE
        status.text = "Checking latest release…"
        io.execute {
            try {
                val c = URL(LATEST_RELEASE).openConnection() as HttpURLConnection
                c.connectTimeout = 8000
                c.readTimeout = 12000
                c.setRequestProperty("Accept", "application/vnd.github+json")
                c.setRequestProperty("User-Agent", "EmuHub-Updater/${BuildConfig.VERSION_NAME}")
                val body = c.inputStream.bufferedReader().use { it.readText() }
                val release = JSONObject(body)
                val tag = release.optString("tag_name")
                val remoteCode = tag.substringAfterLast('v').replace(Regex("[^0-9]"), "").toLongOrNull()
                val assets = release.optJSONArray("assets")
                var apkUrl: String? = null
                var apkName = "EmuHub-update.apk"
                if (assets != null) for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    val n = a.optString("name")
                    if (n.endsWith(".apk", true)) { apkUrl = a.optString("browser_download_url"); apkName = n; break }
                }
                runOnUiThread {
                    progress.visibility = ProgressBar.GONE
                    if (apkUrl.isNullOrBlank()) {
                        status.text = "Release ditemukan, tapi APK tidak tersedia."
                        action.text = "CHECK AGAIN"; action.isEnabled = true
                    } else {
                        val localCode = BuildConfig.VERSION_CODE.toLong()
                        val newer = remoteCode?.let { it > localCode } ?: true
                        if (!newer) {
                            status.text = "Sudah versi terbaru • $tag"
                            action.text = "CHECK AGAIN"; action.isEnabled = true
                        } else {
                            status.text = "Update tersedia • $tag"
                            action.text = "DOWNLOAD & UPDATE"; action.isEnabled = true
                            action.setOnClickListener { prepareEnginePackThenDownload(apkUrl!!, apkName) }
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    progress.visibility = ProgressBar.GONE
                    status.text = "Gagal cek update • ${e.message ?: "network error"}"
                    action.text = "TRY AGAIN"; action.isEnabled = true
                    action.setOnClickListener { checkUpdate() }
                }
            }
        }
    }

    private fun prepareEnginePackThenDownload(url: String, name: String) {
        if (EnginePackManager.isInstalled(this)) { downloadUpdate(url, name); return }
        action.isEnabled = false
        progress.visibility = ProgressBar.VISIBLE
        status.text = "Preparing reusable Engine Pack…"
        io.execute {
            val result = EnginePackManager.installBlocking(applicationContext)
            runOnUiThread {
                if (result.isSuccess) {
                    status.text = "Engine Pack ready • downloading app shell…"
                    downloadUpdate(url, name)
                } else {
                    progress.visibility = ProgressBar.GONE
                    status.text = "Engine Pack gagal • ${result.exceptionOrNull()?.message ?: "unknown error"}"
                    action.text = "TRY AGAIN"
                    action.isEnabled = true
                    action.setOnClickListener { prepareEnginePackThenDownload(url, name) }
                }
            }
        }
    }

    private fun downloadUpdate(url: String, name: String) {
        val dir = File(getExternalFilesDir(null), "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val apk = File(dir, name.ifBlank { "EmuHub-update.apk" })
        pendingApk = apk
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("Emu Hub update")
            .setDescription("Downloading latest signed APK")
            .setMimeType(APK_MIME)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationUri(Uri.fromFile(apk))
        val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        runCatching {
            downloadId = dm.enqueue(request)
            status.text = "Downloading update…"
            progress.visibility = ProgressBar.VISIBLE
            action.isEnabled = false
        }.onFailure {
            Toast.makeText(this, "Download gagal: ${it.message}", Toast.LENGTH_LONG).show()
            action.isEnabled = true
        }
    }

    private fun installApk(apk: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(this)
                .setTitle("Izinkan update")
                .setMessage("Android perlu izin Install unknown apps untuk Emu Hub. Aktifkan sekali; update berikutnya tidak perlu uninstall.")
                .setPositiveButton("OPEN SETTINGS") { _, _ ->
                    startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                }
                .setNegativeButton("Batal", null)
                .show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.updates", apk)
        startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
}
