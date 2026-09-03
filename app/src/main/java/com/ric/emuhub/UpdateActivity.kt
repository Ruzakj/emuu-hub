package com.ric.emuhub

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.FileInputStream
import java.security.MessageDigest
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class UpdateActivity : Activity() {
    companion object {
        private const val LATEST_RELEASE = "https://api.github.com/repos/Ruzakj/emuu-hub/releases/latest"
        private const val APK_MIME = "application/vnd.android.package-archive"
    }

    private class NoStableReleaseException : IOException("No stable release published yet")
    private class ReleaseApiException(val code: Int, message: String) : IOException(message)
    private data class ApkAsset(val url: String, val name: String, val size: Long, val digest: String?)

    private val io = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var action: Button
    private lateinit var testAction: Button
    private lateinit var progress: ProgressBar
    private lateinit var engineState: TextView
    private lateinit var channelState: TextView
    private var pendingApk: File? = null
    private var downloadId: Long = -1L
    @Volatile private var downloadCancelled = false

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) != downloadId) return
            val apk = pendingApk ?: return
            progress.visibility = View.GONE
            if (apk.exists() && apk.length() > 0L) installApk(apk) else {
                status.text = "Download failed"
                action.isEnabled = true
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(downloadReceiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(downloadReceiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        refreshLocalState()
        checkUpdate()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(downloadReceiver) }
        io.shutdownNow()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        refreshLocalState()
        val apk = pendingApk
        if (apk != null && apk.exists() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && packageManager.canRequestPackageInstalls()) installApk(apk)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun rounded(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }
    private fun tv(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        includeFontPadding = false
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun render() {
        window.statusBarColor = 0xFF030406.toInt()
        window.navigationBarColor = 0xFF030406.toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF030406.toInt())
        }
        val scroll = ScrollView(this).apply { isFillViewport = true; overScrollMode = View.OVER_SCROLL_NEVER }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(28))
        }

        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(tv("SYSTEM UPDATE", 10f, 0xFF7E8A9A.toInt(), true).apply { letterSpacing = 0.17f })
        titles.addView(tv("Update Center", 27f, 0xFFFFFFFF.toInt(), true), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(3) })
        top.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(tv("←", 23f, 0xFFFFFFFF.toInt(), true).apply {
            gravity = Gravity.CENTER
            background = rounded(0xFF111720.toInt(), 16, 0xFF283443.toInt())
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(42), dp(42)))
        content.addView(top)

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(17), dp(18), dp(17))
            background = rounded(0xFF101722.toInt(), 24, 0xFF29384C.toInt())
        }
        hero.addView(tv("APP SHELL", 9f, 0xFF8DA2BD.toInt(), true).apply { letterSpacing = 0.16f })
        hero.addView(tv("Emu Hub ${BuildConfig.VERSION_NAME}", 22f, 0xFFFFFFFF.toInt(), true), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(7) })
        hero.addView(tv("Build ${BuildConfig.VERSION_CODE} • signed in-app update channel", 10.5f, 0xFF8998AA.toInt()), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        content.addView(hero, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(17) })

        val stateRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val engineCard = stateCard("ENGINE PACK")
        engineState = engineCard.second
        stateRow.addView(engineCard.first, LinearLayout.LayoutParams(0, dp(86), 1f).apply { rightMargin = dp(5) })
        val channelCard = stateCard("UPDATE CHANNEL")
        channelState = channelCard.second
        stateRow.addView(channelCard.first, LinearLayout.LayoutParams(0, dp(86), 1f).apply { leftMargin = dp(5) })
        content.addView(stateRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(86)).apply { topMargin = dp(11) })

        val checkCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(15), dp(16), dp(15))
            background = rounded(0xFF0B1017.toInt(), 21, 0xFF202B39.toInt())
        }
        checkCard.addView(tv("LATEST RELEASE", 9f, 0xFF738094.toInt(), true).apply { letterSpacing = 0.14f })
        status = tv("Checking GitHub Release…", 14f, 0xFFF2F5F8.toInt(), true)
        checkCard.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(9) })
        progress = ProgressBar(this).apply { isIndeterminate = true }
        checkCard.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10); gravity = Gravity.CENTER_HORIZONTAL })
        content.addView(checkCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(11) })

        action = Button(this).apply {
            text = "CHECK FOR UPDATE"
            isEnabled = false
            setTextColor(0xFF05070A.toInt())
            background = rounded(0xFFF4F7FA.toInt(), 15)
            setTypeface(typeface, Typeface.BOLD)
            setOnClickListener { checkUpdate() }
        }
        content.addView(action, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply { topMargin = dp(12) })

        testAction = Button(this).apply {
            text = "RUN UPDATE SELF-TEST"
            setTextColor(0xFFF3F6FA.toInt())
            background = rounded(0xFF111820.toInt(), 15, 0xFF2A394B.toInt())
            setTypeface(typeface, Typeface.BOLD)
            setOnClickListener { runSelfTest() }
        }
        content.addView(testAction, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply { topMargin = dp(9) })

        content.addView(tv("SELF-TEST only checks release API, APK asset discovery, Engine Pack status, and install permission. It never downloads or installs anything.", 9.5f, 0xFF687587.toInt()), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })

        scroll.addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun stateCard(title: String): Pair<LinearLayout, TextView> {
        val value = tv("…", 11f, 0xFFF4F7FA.toInt(), true)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(0xFF0B1017.toInt(), 19, 0xFF202B39.toInt())
            addView(tv(title, 8.5f, 0xFF6D7A8D.toInt(), true).apply { letterSpacing = 0.12f })
            addView(value, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(7) })
        }
        return card to value
    }

    private fun refreshLocalState() {
        engineState.text = if (EnginePackManager.isInstalled(this)) "READY" else "BOOTSTRAP"
        channelState.text = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()) "INSTALL READY" else "PERMISSION NEEDED"
    }

    private fun checkUpdate() {
        action.isEnabled = false
        progress.visibility = View.VISIBLE
        status.text = "Checking latest release…"
        io.execute {
            try {
                val release = fetchLatestRelease()
                val tag = release.optString("tag_name")
                val remoteCode = tag.substringAfterLast('v').replace(Regex("[^0-9]"), "").toLongOrNull()
                val apk = findApkAsset(release)
                runOnUiThread {
                    progress.visibility = View.GONE
                    if (apk == null) {
                        status.text = "Release found • APK asset missing"
                        action.text = "CHECK AGAIN"; action.isEnabled = true
                        action.setOnClickListener { checkUpdate() }
                    } else {
                        val localCode = BuildConfig.VERSION_CODE.toLong()
                        val newer = remoteCode?.let { it > localCode } ?: true
                        if (!newer) {
                            status.text = "You're current • $tag"
                            action.text = "CHECK AGAIN"; action.isEnabled = true
                            action.setOnClickListener { checkUpdate() }
                        } else {
                            status.text = "Update ready • $tag"
                            action.text = "DOWNLOAD & UPDATE"; action.isEnabled = true
                            action.setOnClickListener { prepareEnginePackThenDownload(apk.first, apk.second) }
                        }
                    }
                }
            } catch (_: NoStableReleaseException) {
                runOnUiThread {
                    progress.visibility = View.GONE
                    status.text = "No stable update published yet"
                    action.text = "CHECK AGAIN"
                    action.isEnabled = true
                    action.setOnClickListener { checkUpdate() }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    progress.visibility = View.GONE
                    status.text = "Update service unavailable • ${friendlyError(e)}"
                    action.text = "TRY AGAIN"; action.isEnabled = true
                    action.setOnClickListener { checkUpdate() }
                }
            }
        }
    }

    private fun runSelfTest() {
        testAction.isEnabled = false
        progress.visibility = View.VISIBLE
        status.text = "Running updater self-test…"
        io.execute {
            val results = mutableListOf<String>()
            results += "Engine Pack: " + if (EnginePackManager.isInstalled(applicationContext)) "PASS" else "READY TO BOOTSTRAP"
            results += "Install permission: " + if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()) "PASS" else "USER ACTION NEEDED"
            try {
                val release = fetchLatestRelease()
                results += "GitHub release API: PASS"
                results += "Latest tag: ${release.optString("tag_name", "unknown")}"
                val apk = findApkAsset(release)
                results += "APK asset: " + if (apk != null) "PASS • ${apk.name} • ${formatBytes(apk.size)}" else "FAIL • missing APK"
            } catch (_: NoStableReleaseException) {
                results += "GitHub release API: PASS"
                results += "Stable release: NOT PUBLISHED YET"
                results += "APK asset: WAITING FOR FIRST STABLE RELEASE"
            } catch (e: Exception) {
                results += "GitHub release API: FAIL • ${friendlyError(e)}"
            }
            runOnUiThread {
                progress.visibility = View.GONE
                testAction.isEnabled = true
                status.text = results.joinToString("\n")
                refreshLocalState()
            }
        }
    }

    private fun fetchLatestRelease(): JSONObject {
        val c = URL(LATEST_RELEASE).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 12000
        c.instanceFollowRedirects = true
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        c.setRequestProperty("User-Agent", "EmuHub-Updater/${BuildConfig.VERSION_NAME}")
        return try {
            val code = c.responseCode
            if (code == HttpURLConnection.HTTP_NOT_FOUND) throw NoStableReleaseException()
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw ReleaseApiException(code, "GitHub HTTP $code")
            if (body.isBlank()) throw IOException("Empty release response")
            JSONObject(body)
        } finally {
            c.disconnect()
        }
    }

    private fun friendlyError(e: Exception): String = when (e) {
        is ReleaseApiException -> "GitHub HTTP ${e.code}"
        is java.net.SocketTimeoutException -> "connection timed out"
        is java.net.UnknownHostException -> "no internet connection"
        else -> e.message?.substringBefore("https://")?.trim()?.trimEnd('•')?.takeIf { it.isNotBlank() } ?: "network error"
    }

    private fun findApkAsset(release: JSONObject): ApkAsset? {
        val assets = release.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val n = a.optString("name")
            val url = a.optString("browser_download_url")
            if (n.endsWith(".apk", true) && url.isNotBlank()) return ApkAsset(url, n, a.optLong("size", -1L), a.optString("digest").takeIf { it.startsWith("sha256:") })
        }
        return null
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 0L -> "size unknown"
        bytes >= 1024L * 1024L -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0)
        bytes >= 1024L -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun prepareEnginePackThenDownload(asset: ApkAsset) {
        if (EnginePackManager.isInstalled(this)) { downloadUpdate(asset); return }
        action.isEnabled = false; progress.visibility = View.VISIBLE; status.text = "Preparing reusable Engine Pack…"
        io.execute {
            val result = EnginePackManager.installBlocking(applicationContext)
            runOnUiThread {
                refreshLocalState()
                if (result.isSuccess) { status.text = "Engine Pack ready • downloading ${formatBytes(asset.size)}…"; downloadUpdate(asset) }
                else { progress.visibility = View.GONE; status.text = "Engine Pack failed • ${result.exceptionOrNull()?.message ?: "unknown error"}"; action.text = "TRY AGAIN"; action.isEnabled = true; action.setOnClickListener { prepareEnginePackThenDownload(asset) } }
            }
        }
    }

    private fun downloadUpdate(asset: ApkAsset) {
        val dir = File(getExternalFilesDir(null), "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.deleteRecursively() }
        val apk = File(dir, asset.name.ifBlank { "EmuHub-update.apk" })
        pendingApk = apk; downloadCancelled = false
        val request = DownloadManager.Request(Uri.parse(asset.url)).setTitle("Emu Hub update").setDescription("Downloading ${formatBytes(asset.size)} signed update").setMimeType(APK_MIME).setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED).setDestinationUri(Uri.fromFile(apk))
        val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        runCatching {
            downloadId = dm.enqueue(request)
            progress.isIndeterminate = false; progress.max = 100; progress.progress = 0; progress.visibility = View.VISIBLE
            action.text = "CANCEL DOWNLOAD"; action.isEnabled = true
            action.setOnClickListener { downloadCancelled = true; dm.remove(downloadId); apk.delete(); pendingApk = null; progress.visibility = View.GONE; status.text = "Download cancelled • temporary file cleaned"; action.text = "CHECK AGAIN"; action.setOnClickListener { checkUpdate() } }
            Thread({ monitorDownload(dm, asset, apk) }, "emuhub-update-progress").start()
        }.onFailure { apk.delete(); pendingApk = null; progress.visibility = View.GONE; status.text = "Download failed • temporary file cleaned"; action.text = "TRY AGAIN"; action.isEnabled = true; action.setOnClickListener { downloadUpdate(asset) } }
    }

    private fun monitorDownload(dm: DownloadManager, asset: ApkAsset, apk: File) {
        while (!downloadCancelled && downloadId >= 0L) {
            var done = false
            runCatching {
                dm.query(DownloadManager.Query().setFilterById(downloadId)).use { c ->
                    if (!c.moveToFirst()) { done = true; return@use }
                    val state = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    val got = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)).takeIf { it > 0 } ?: asset.size
                    if (state == DownloadManager.STATUS_SUCCESSFUL) done = true else if (state == DownloadManager.STATUS_FAILED) throw IOException("DownloadManager failed")
                    if (total > 0) runOnUiThread { val pct = ((got * 100L) / total).coerceIn(0L, 100L).toInt(); progress.progress = pct; status.text = "Downloading • $pct% • ${formatBytes(got)} / ${formatBytes(total)}" }
                }
            }.onFailure { dm.remove(downloadId); apk.delete(); pendingApk = null; done = true; runOnUiThread { progress.visibility = View.GONE; status.text = "Download failed • temporary file cleaned"; action.text = "RETRY"; action.isEnabled = true; action.setOnClickListener { downloadUpdate(asset) } } }
            if (done) break
            try { Thread.sleep(500) } catch (_: InterruptedException) { break }
        }
        if (downloadCancelled || !apk.isFile) return
        runCatching { verifyDownloadedApk(apk, asset) }.onSuccess { runOnUiThread { progress.progress = 100; status.text = "Verified • ${formatBytes(apk.length())} • ready to install"; action.text = "INSTALL UPDATE"; action.isEnabled = true; action.setOnClickListener { installApk(apk) }; installApk(apk) } }.onFailure { apk.delete(); pendingApk = null; runOnUiThread { progress.visibility = View.GONE; status.text = "Update file corrupt • cleaned safely"; action.text = "RETRY DOWNLOAD"; action.isEnabled = true; action.setOnClickListener { downloadUpdate(asset) } } }
    }

    private fun verifyDownloadedApk(file: File, asset: ApkAsset) {
        require(file.isFile && file.length() > 0L) { "APK missing" }
        if (asset.size > 0L) require(file.length() == asset.size) { "APK size mismatch" }
        val expected = asset.digest?.removePrefix("sha256:") ?: return
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).buffered().use { input -> val buf = ByteArray(1024 * 1024); while (true) { val n = input.read(buf); if (n <= 0) break; md.update(buf, 0, n) } }
        val actual = md.digest().joinToString("") { "%02x".format(it) }
        require(actual.equals(expected, true)) { "APK checksum mismatch" }
    }

    private fun installApk(apk: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(this)
                .setTitle("Allow app updates")
                .setMessage("Android needs Install unknown apps permission for Emu Hub. Enable it once so future signed updates can install without uninstalling the app.")
                .setPositiveButton("OPEN SETTINGS") { _, _ ->
                    startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                }
                .setNegativeButton("CANCEL", null)
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
