package com.ric.emuhub

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.RandomAccessFile

class MainActivity : Activity() {
    companion object {
        private const val REQUEST_ROM = 1001
        private const val REQUEST_FOLDER = 1002
        private const val PREFS = "emuhub_library"
        private const val KEY_ROM_TREE = "rom_tree"
        private val INTERNAL = setOf("gb","gbc","gba","nes","sfc","smc","bin","cue","chd","iso","cso")
        private val SWITCH = setOf("xci","nsp","nro")
        private val RECOGNIZED = INTERNAL + SWITCH + "ecm"
        private val EDEN_PACKAGES = listOf(
            "com.miHoYo.Yuanshen",
            "com.miHoYo.Yunashen",
            "com.miHoYo.Yuanshen.nightly",
            "dev.eden.eden_emulator",
            "dev.eden.eden_nightly"
        )
    }

    private lateinit var library: LinearLayout
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        renderHome()
        scanSavedFolder()
    }

    private fun renderHome() {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 24, 28, 24)
            setBackgroundColor(0xFF0A0A0D.toInt())
        }
        outer.addView(TextView(this).apply {
            text = "EMU HUB"
            textSize = 30f
            setTextColor(0xFFFFFFFF.toInt())
        })
        status = TextView(this).apply {
            text = "Library offline • internal cores + Eden Switch"
            textSize = 14f
            setTextColor(0xFFB8B8C2.toInt())
            setPadding(0, 6, 0, 14)
        }
        outer.addView(status)

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(Button(this).apply { text = "SCAN FOLDER"; setOnClickListener { chooseRomFolder() } }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(Button(this).apply { text = "SELECT GAME"; setOnClickListener { openRomPicker() } }, LinearLayout.LayoutParams(0, -2, 1f))
        outer.addView(actions)

        library = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 14, 0, 20)
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(library, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        outer.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(outer)
    }

    private fun chooseRomFolder() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }, REQUEST_FOLDER)
    }

    private fun openRomPicker() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, REQUEST_ROM)
    }

    private fun scanSavedFolder() {
        val saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_ROM_TREE, null) ?: run {
            status.text = "Pilih folder ROM sekali, lalu game akan muncul otomatis di sini."
            return
        }
        scanTree(Uri.parse(saved))
    }

    private fun scanTree(treeUri: Uri) {
        status.text = "Scanning ROM..."
        library.removeAllViews()
        Thread {
            val root = DocumentFile.fromTreeUri(this, treeUri)
            val games = mutableListOf<DocumentFile>()
            if (root != null) collectGames(root, games, 600)
            games.sortBy { it.name?.lowercase() ?: "" }
            runOnUiThread {
                status.text = if (games.isEmpty()) "Tidak ada ROM yang didukung di folder ini." else "${games.size} game ditemukan • tap untuk main"
                games.forEach { addGameCard(it) }
            }
        }.start()
    }

    private fun collectGames(dir: DocumentFile, out: MutableList<DocumentFile>, limit: Int) {
        if (out.size >= limit) return
        runCatching { dir.listFiles().toList() }.getOrDefault(emptyList()).forEach { file ->
            if (out.size >= limit) return
            if (file.isDirectory) collectGames(file, out, limit)
            else if (extension(file.name) in RECOGNIZED) out.add(file)
        }
    }

    private fun addGameCard(doc: DocumentFile) {
        val name = doc.name ?: "Game"
        val ext = extension(name)
        val system = systemName(ext)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 16, 22, 16)
            isClickable = true
            isFocusable = true
            setBackgroundColor(0xFF17171D.toInt())
        }
        card.addView(TextView(this).apply {
            text = name.substringBeforeLast('.', name)
            textSize = 17f
            setTextColor(0xFFFFFFFF.toInt())
        })
        card.addView(TextView(this).apply {
            text = system
            textSize = 12f
            setTextColor(if (ext in SWITCH) 0xFF8FD3FF.toInt() else 0xFFAAAAAF.toInt())
        })
        card.setOnClickListener { openLibraryGame(doc.uri, name, ext) }
        library.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 10 })
    }

    private fun openLibraryGame(uri: Uri, name: String, ext: String) {
        if (ext in SWITCH) {
            launchEden(uri)
            return
        }
        if (ext == "ecm") {
            Toast.makeText(this, "ECM perlu di-decode dulu ke BIN/ISO", Toast.LENGTH_LONG).show()
            return
        }
        copyAndLaunchInternal(uri, name, ext)
    }

    private fun launchEden(uri: Uri) {
        val pkg = EDEN_PACKAGES.firstOrNull { packageManager.getLaunchIntentForPackage(it) != null }
        if (pkg == null) {
            Toast.makeText(this, "Eden / Eden Optimized tidak terdeteksi.", Toast.LENGTH_LONG).show()
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/octet-stream")
                setPackage(pkg)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newRawUri("Switch ROM", uri)
            })
        } catch (_: ActivityNotFoundException) {
            packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it) }
                ?: Toast.makeText(this, "Tidak dapat membuka Eden.", Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it) }
        }
    }

    private fun displayName(uri: Uri): String? {
        if (uri.scheme == "content") contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) { val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (i >= 0) return c.getString(i) }
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun extension(name: String?): String = name.orEmpty().substringAfterLast('.', "").lowercase()

    private fun systemName(ext: String): String = when (ext) {
        "gb","gbc","gba" -> "Game Boy • mGBA"
        "nes" -> "NES • FCEUmm"
        "sfc","smc" -> "SNES • Snes9x"
        "bin","cue","chd" -> "PlayStation • PCSX-ReARMed"
        "iso" -> "PSP / PlayStation • auto detect"
        "cso" -> "PSP • PPSSPP"
        "xci","nsp","nro" -> "Switch • Eden Optimized"
        "ecm" -> "PlayStation • ECM"
        else -> "ROM"
    }

    private fun copyAndLaunchInternal(uri: Uri, name: String, ext: String) {
        try {
            status.text = "Membuka $name..."
            val dir = File(cacheDir, "roms").apply { mkdirs() }
            val safe = name.replace(Regex("[^A-Za-z0-9._ -]"), "_")
            val out = File(dir, safe)
            contentResolver.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } }
                ?: throw IllegalStateException("ROM tidak dapat dibaca")
            val coreId = coreIdFor(ext, out)
            startActivity(Intent(this, GameActivity::class.java)
                .putExtra("romPath", out.absolutePath)
                .putExtra("coreId", coreId)
                .putExtra("romName", name))
        } catch (e: Exception) {
            status.text = "Gagal membuka ROM: ${e.message}"
        }
    }

    private fun containsAscii(file: File, token: String, maxBytes: Long = 64L * 1024 * 1024): Boolean {
        val needle = token.toByteArray(Charsets.US_ASCII)
        return runCatching {
            file.inputStream().buffered().use { input ->
                val buf = ByteArray(256 * 1024); var carry = ByteArray(0); var total = 0L
                while (total < maxBytes) {
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), maxBytes-total).toInt()); if (n <= 0) break; total += n
                    val data = ByteArray(carry.size+n); carry.copyInto(data); buf.copyInto(data,carry.size,0,n)
                    outer@ for (i in 0..data.size-needle.size) { for (j in needle.indices) if (data[i+j] != needle[j]) continue@outer; return@use true }
                    val keep=minOf(needle.size-1,data.size); carry=data.copyOfRange(data.size-keep,data.size)
                }; false
            }
        }.getOrDefault(false)
    }

    private fun isPspIso(file: File): Boolean = containsAscii(file,"PSP_GAME") || containsAscii(file,"UMD_VIDEO") || containsAscii(file,"UMD_AUDIO") || runCatching {
        RandomAccessFile(file,"r").use { r -> if(r.length()<18L*2048)return@use false;val p=ByteArray(2048);r.seek(16L*2048);r.readFully(p);String(p,1,5,Charsets.US_ASCII)=="CD001"&&containsAscii(file,"PSP",96L*1024*1024) }
    }.getOrDefault(false)

    private fun coreIdFor(ext: String, file: File): String = when(ext) {
        "nes" -> "fceumm"
        "sfc","smc" -> "snes9x"
        "bin","cue","chd" -> "pcsx"
        "cso" -> "ppsspp"
        "iso" -> if (isPspIso(file)) "ppsspp" else if (containsAscii(file,"SYSTEM.CNF") || containsAscii(file,"PS-X EXE") || containsAscii(file,"PLAYSTATION")) "pcsx" else "ppsspp"
        else -> "mgba"
    }

    @Deprecated("Framework compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        if (requestCode == REQUEST_FOLDER) {
            val uri = data?.data ?: return
            val flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            runCatching { contentResolver.takePersistableUriPermission(uri, flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_ROM_TREE, uri.toString()).apply()
            scanTree(uri)
            return
        }
        if (requestCode == REQUEST_ROM) {
            val uri = data?.data ?: return
            val name = displayName(uri) ?: "ROM"
            val ext = extension(name)
            if (ext in SWITCH) launchEden(uri) else if (ext in INTERNAL) copyAndLaunchInternal(uri,name,ext)
            else Toast.makeText(this,"Format belum didukung: .$ext",Toast.LENGTH_LONG).show()
        }
    }
}
