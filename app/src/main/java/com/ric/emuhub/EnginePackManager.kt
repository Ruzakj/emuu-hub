package com.ric.emuhub

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream

object EnginePackManager {
    const val ENGINE_VERSION = 1
    private const val RELEASE_API = "https://api.github.com/repos/Ruzakj/emuu-hub/releases/tags/engine-v1"
    private const val ZIP_NAME = "EmuHub-EnginePack-v1-arm64.zip"
    private const val SHA_NAME = "$ZIP_NAME.sha256"
    private const val BUFFER = 1024 * 1024

    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var bootstrapping = false

    private fun root(context: Context) = File(context.filesDir, "engine_packs")
    private fun current(context: Context) = File(root(context), "current")
    private fun marker(context: Context) = File(current(context), ".installed")

    fun isInstalled(context: Context): Boolean {
        val m = marker(context)
        return m.isFile && m.readText().trim() == ENGINE_VERSION.toString() &&
            File(current(context), "manifest.json").isFile
    }

    fun corePath(context: Context, fileName: String): String? {
        if (!isInstalled(context)) return null
        return File(current(context), "lib/$fileName").takeIf { it.isFile && it.length() > 0L }?.absolutePath
    }

    fun ppssppAssetsDir(context: Context): File? {
        if (!isInstalled(context)) return null
        return File(current(context), "assets/PPSSPP").takeIf { it.isDirectory }
    }

    fun bootstrapAsync(context: Context) {
        if (isInstalled(context) || bootstrapping) return
        synchronized(this) {
            if (isInstalled(context) || bootstrapping) return
            bootstrapping = true
        }
        val app = context.applicationContext
        executor.execute {
            try { installBlocking(app) } finally { bootstrapping = false }
        }
    }

    @Synchronized
    fun installBlocking(context: Context): Result<Unit> = runCatching {
        if (isInstalled(context)) return@runCatching
        val base = root(context).apply { mkdirs() }
        val work = File(base, "incoming-$ENGINE_VERSION").apply { deleteRecursively(); mkdirs() }
        val zip = File(work, ZIP_NAME)
        val shaFile = File(work, SHA_NAME)

        val release = JSONObject(httpText(RELEASE_API))
        val assets = release.getJSONArray("assets")
        var zipUrl: String? = null
        var shaUrl: String? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            when (a.optString("name")) {
                ZIP_NAME -> zipUrl = a.optString("browser_download_url")
                SHA_NAME -> shaUrl = a.optString("browser_download_url")
            }
        }
        require(!zipUrl.isNullOrBlank()) { "Engine Pack ZIP tidak ditemukan" }
        require(!shaUrl.isNullOrBlank()) { "Engine Pack checksum tidak ditemukan" }

        download(zipUrl!!, zip)
        download(shaUrl!!, shaFile)
        val expectedArchiveSha = shaFile.readText().trim().split(Regex("\\s+")).first().lowercase()
        require(expectedArchiveSha.length == 64) { "Checksum Engine Pack tidak valid" }
        require(sha256(zip) == expectedArchiveSha) { "Checksum Engine Pack tidak cocok" }

        val unpacked = File(work, "unpacked").apply { mkdirs() }
        unzipSafe(zip, unpacked)
        verifyManifest(unpacked)
        File(unpacked, ".installed").writeText(ENGINE_VERSION.toString())

        val target = current(context)
        val backup = File(base, "previous").apply { deleteRecursively() }
        if (target.exists() && !target.renameTo(backup)) target.deleteRecursively()
        require(unpacked.renameTo(target)) { "Gagal mengaktifkan Engine Pack" }
        backup.deleteRecursively()
        work.deleteRecursively()
    }

    private fun verifyManifest(dir: File) {
        val manifestFile = File(dir, "manifest.json")
        require(manifestFile.isFile) { "manifest.json Engine Pack tidak ada" }
        val manifest = JSONObject(manifestFile.readText())
        require(manifest.optInt("engineVersion") == ENGINE_VERSION) { "Versi Engine Pack tidak cocok" }
        val files = manifest.getJSONArray("files")
        for (i in 0 until files.length()) {
            val entry = files.getJSONObject(i)
            val rel = entry.getString("path")
            val expected = entry.getString("sha256").lowercase()
            val f = File(dir, rel)
            require(f.canonicalPath.startsWith(dir.canonicalPath + File.separator)) { "Path Engine Pack tidak aman" }
            require(f.isFile && sha256(f) == expected) { "File Engine Pack rusak: $rel" }
        }
    }

    private fun unzipSafe(zip: File, dest: File) {
        val rootPath = dest.canonicalPath + File.separator
        ZipInputStream(FileInputStream(zip).buffered(BUFFER)).use { zin ->
            var entry = zin.nextEntry
            while (entry != null) {
                val out = File(dest, entry.name)
                require(out.canonicalPath.startsWith(rootPath)) { "ZIP Engine Pack tidak aman" }
                if (entry.isDirectory) out.mkdirs() else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).buffered(BUFFER).use { output -> zin.copyTo(output, BUFFER) }
                }
                zin.closeEntry()
                entry = zin.nextEntry
            }
        }
    }

    private fun httpText(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 20_000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "EmuHub-EnginePack/$ENGINE_VERSION")
        require(c.responseCode in 200..299) { "HTTP ${c.responseCode}" }
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    private fun download(url: String, out: File) {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 60_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "EmuHub-EnginePack/$ENGINE_VERSION")
        require(c.responseCode in 200..299) { "Download HTTP ${c.responseCode}" }
        out.parentFile?.mkdirs()
        c.inputStream.buffered(BUFFER).use { input ->
            FileOutputStream(out).buffered(BUFFER).use { output -> input.copyTo(output, BUFFER) }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).buffered(BUFFER).use { input ->
            val buf = ByteArray(BUFFER)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
