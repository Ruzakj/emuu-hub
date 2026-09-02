package com.ric.emuhub

import android.content.Context
import android.net.Uri
import com.github.junrar.Archive
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipFile

object ArchiveHelper {
    private val ROM_EXTENSIONS = setOf("gb","gbc","gba","nes","sfc","smc","bin","cue","chd","iso","cso","ecm","xci","nsp","nro")
    val ARCHIVE_EXTENSIONS = setOf("zip","7z","rar","tar","tgz","gz","bz2","xz","tbz2","txz")
    private const val MAX_ENTRIES = 4096
    private const val MAX_TOTAL_BYTES = 10L * 1024L * 1024L * 1024L

    data class ExtractedRom(val file: File, val displayName: String, val ext: String)
    data class Session(val root: File, val roms: List<ExtractedRom>)

    fun cleanupStale(cacheDir: File) {
        val parent = File(cacheDir, "archive_sessions")
        if (!parent.exists()) return
        val cutoff = System.currentTimeMillis() - 60L * 60L * 1000L
        parent.listFiles()?.forEach { if (it.lastModified() < cutoff) it.deleteRecursively() }
    }

    fun extract(context: Context, uri: Uri, archiveName: String): Session {
        val parent = File(context.cacheDir, "archive_sessions").apply { mkdirs() }
        val root = File(parent, UUID.randomUUID().toString()).apply { mkdirs() }
        val extractRoot = File(root, "files").apply { mkdirs() }
        val suffix = archiveName.substringAfterLast('.', "archive").lowercase(Locale.US).replace(Regex("[^a-z0-9]"), "")
        val source = File(root, "source.${if (suffix.isBlank()) "archive" else suffix}")
        try {
            context.contentResolver.openInputStream(uri)?.use { input -> source.outputStream().buffered().use { output -> input.copyTo(output) } }
                ?: error("Archive tidak dapat dibaca")
            val extracted = when {
                archiveName.lowercase(Locale.US).endsWith(".7z") -> extract7z(source, extractRoot)
                archiveName.lowercase(Locale.US).endsWith(".rar") -> extractRar(source, extractRoot)
                archiveName.lowercase(Locale.US).endsWith(".zip") -> extractZip(source, extractRoot)
                isTarName(archiveName) -> extractTar(source, extractRoot, archiveName)
                else -> extractSingleCompressed(source, extractRoot, archiveName)
            }
            source.delete()
            val playable = preferCueFiles(extracted)
            if (playable.isEmpty()) error("Tidak ada ROM yang didukung di dalam archive")
            return Session(root, playable.sortedBy { it.displayName.lowercase(Locale.US) })
        } catch (t: Throwable) {
            root.deleteRecursively()
            throw t
        }
    }

    private fun isTarName(name: String): Boolean {
        val n = name.lowercase(Locale.US)
        return n.endsWith(".tar") || n.endsWith(".tgz") || n.endsWith(".tar.gz") || n.endsWith(".tbz2") || n.endsWith(".tar.bz2") || n.endsWith(".txz") || n.endsWith(".tar.xz")
    }

    private fun safeTarget(root: File, entryName: String): File? {
        val clean = entryName.replace('\\', '/').trimStart('/')
        if (clean.isBlank()) return null
        val target = File(root, clean).canonicalFile
        val base = root.canonicalFile.path + File.separator
        return if (target.path.startsWith(base)) target else null
    }

    private fun romExt(name: String) = name.substringAfterLast('.', "").lowercase(Locale.US)

    private fun copyLimited(input: InputStream, target: File, total: LongArray) {
        target.parentFile?.mkdirs()
        val buffer = ByteArray(256 * 1024)
        FileOutputStream(target).buffered().use { output ->
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                total[0] += n
                if (total[0] > MAX_TOTAL_BYTES) error("Archive terlalu besar")
                output.write(buffer, 0, n)
            }
        }
    }

    private fun extractZip(source: File, root: File): List<ExtractedRom> {
        val result = mutableListOf<ExtractedRom>()
        val total = longArrayOf(0L)
        ZipFile(source).use { zip ->
            val entries = zip.entries()
            var seen = 0
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (++seen > MAX_ENTRIES) error("Terlalu banyak file di dalam archive")
                if (entry.isDirectory) continue
                val ext = romExt(entry.name)
                if (ext !in ROM_EXTENSIONS) continue
                val target = safeTarget(root, entry.name) ?: continue
                zip.getInputStream(entry).buffered().use { copyLimited(it, target, total) }
                result += ExtractedRom(target, target.name, ext)
            }
        }
        return result
    }

    @Suppress("DEPRECATION")
    private fun extract7z(source: File, root: File): List<ExtractedRom> {
        val result = mutableListOf<ExtractedRom>()
        val total = longArrayOf(0L)
        SevenZFile(source).use { seven ->
            var seen = 0
            while (true) {
                val entry = seven.nextEntry ?: break
                if (++seen > MAX_ENTRIES) error("Terlalu banyak file di dalam archive")
                if (entry.isDirectory) continue
                val ext = romExt(entry.name)
                if (ext !in ROM_EXTENSIONS) continue
                val target = safeTarget(root, entry.name) ?: continue
                target.parentFile?.mkdirs()
                val buffer = ByteArray(256 * 1024)
                FileOutputStream(target).buffered().use { output ->
                    while (true) {
                        val n = seven.read(buffer)
                        if (n <= 0) break
                        total[0] += n
                        if (total[0] > MAX_TOTAL_BYTES) error("Archive terlalu besar")
                        output.write(buffer, 0, n)
                    }
                }
                result += ExtractedRom(target, target.name, ext)
            }
        }
        return result
    }

    private fun extractRar(source: File, root: File): List<ExtractedRom> {
        val result = mutableListOf<ExtractedRom>()
        val total = longArrayOf(0L)
        Archive(source).use { rar ->
            var seen = 0
            while (true) {
                val entry = rar.nextFileHeader() ?: break
                if (++seen > MAX_ENTRIES) error("Terlalu banyak file di dalam archive")
                if (entry.isDirectory) continue
                val entryName = entry.fileNameW.takeIf { it.isNotBlank() } ?: entry.fileNameString
                val ext = romExt(entryName)
                if (ext !in ROM_EXTENSIONS) continue
                val target = safeTarget(root, entryName) ?: continue
                target.parentFile?.mkdirs()
                FileOutputStream(target).buffered().use { output ->
                    rar.extractFile(entry, output)
                }
                total[0] += target.length()
                if (total[0] > MAX_TOTAL_BYTES) error("Archive terlalu besar")
                result += ExtractedRom(target, target.name, ext)
            }
        }
        return result
    }

    private fun extractTar(source: File, root: File, archiveName: String): List<ExtractedRom> {
        val n = archiveName.lowercase(Locale.US)
        val raw = BufferedInputStream(source.inputStream())
        val decompressed: InputStream = when {
            n.endsWith(".tgz") || n.endsWith(".tar.gz") -> GzipCompressorInputStream(raw)
            n.endsWith(".tbz2") || n.endsWith(".tar.bz2") -> BZip2CompressorInputStream(raw)
            n.endsWith(".txz") || n.endsWith(".tar.xz") -> XZCompressorInputStream(raw)
            else -> raw
        }
        val result = mutableListOf<ExtractedRom>()
        val total = longArrayOf(0L)
        TarArchiveInputStream(decompressed).use { tar ->
            var seen = 0
            while (true) {
                val entry = tar.nextTarEntry ?: break
                if (++seen > MAX_ENTRIES) error("Terlalu banyak file di dalam archive")
                if (entry.isDirectory) continue
                val ext = romExt(entry.name)
                if (ext !in ROM_EXTENSIONS) continue
                val target = safeTarget(root, entry.name) ?: continue
                copyLimited(tar, target, total)
                result += ExtractedRom(target, target.name, ext)
            }
        }
        return result
    }

    private fun extractSingleCompressed(source: File, root: File, archiveName: String): List<ExtractedRom> {
        val n = archiveName.lowercase(Locale.US)
        val outputName = when {
            n.endsWith(".gz") -> archiveName.dropLast(3)
            n.endsWith(".bz2") -> archiveName.dropLast(4)
            n.endsWith(".xz") -> archiveName.dropLast(3)
            else -> error("Format archive belum didukung")
        }
        val ext = romExt(outputName)
        if (ext !in ROM_EXTENSIONS) error("Isi compressed file bukan ROM yang didukung")
        val raw = BufferedInputStream(source.inputStream())
        val input: InputStream = when {
            n.endsWith(".gz") -> GzipCompressorInputStream(raw)
            n.endsWith(".bz2") -> BZip2CompressorInputStream(raw)
            n.endsWith(".xz") -> XZCompressorInputStream(raw)
            else -> raw
        }
        val target = safeTarget(root, outputName) ?: error("Nama file archive tidak aman")
        input.use { copyLimited(it, target, longArrayOf(0L)) }
        return listOf(ExtractedRom(target, target.name, ext))
    }

    private fun preferCueFiles(files: List<ExtractedRom>): List<ExtractedRom> {
        val cueParents = files.filter { it.ext == "cue" }.map { it.file.parentFile?.canonicalPath }.toSet()
        return files.filterNot { it.ext == "bin" && it.file.parentFile?.canonicalPath in cueParents }
    }
}
