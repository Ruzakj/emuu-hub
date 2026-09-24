package com.ric.emuhub

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

object BuiltinRomManager {
    private const val TAG = "BuiltinRomManager"
    private const val PREFS = "emuhub_library"
    private const val CACHE_KEY = "library_cache_v2"
    private const val ASSET_ROOT = "builtin-roms"
    private const val MARKER = ".builtin_roms_v1"
    private const val MARKER_CONTENT = "1"
    private const val MAX_MARKER_BYTES = 64L
    private val supported = setOf("gb", "gbc", "gba", "nes", "sfc", "smc")

    private data class ScannedRom(
        val file: File,
        val relativePath: String,
        val sortKey: String,
        val extension: String,
        val folder: String,
    )

    fun install(context: Context) {
        val targetRoot = File(context.filesDir, ASSET_ROOT)
        val marker = File(targetRoot, MARKER)
        val markerValid = marker.isFile && marker.length() <= MAX_MARKER_BYTES &&
            runCatching { marker.readText().trim() == MARKER_CONTENT }.getOrDefault(false)
        val starterPackValid = markerValid && containsSupportedRom(targetRoot)
        if (!starterPackValid) {
            val installed = runCatching {
                ensureDirectory(targetRoot)
                if (marker.exists()) {
                    check(marker.delete()) { "Unable to clear stale built-in ROM marker" }
                }
                copyAssetTree(context, ASSET_ROOT, targetRoot)
                check(containsSupportedRom(targetRoot)) {
                    "Built-in ROM starter pack contains no supported ROMs"
                }
                marker.writeText(MARKER_CONTENT)
            }.onFailure { error ->
                Log.w(TAG, "Unable to install built-in ROM starter pack", error)
            }.isSuccess
            if (!installed) return
        }
        runCatching { mergeIntoLibraryCache(context, targetRoot) }
            .onFailure { error -> Log.w(TAG, "Unable to merge built-in ROMs into library cache", error) }
    }

    private fun containsSupportedRom(root: File): Boolean =
        root.isDirectory && root.walkTopDown().any { supportedExtension(it) != null }

    private fun normalizedExtension(file: File): String = file.extension.lowercase(Locale.ROOT)

    private fun supportedExtension(file: File): String? {
        if (!file.isFile || file.length() <= 0L) return null
        return normalizedExtension(file).takeIf { it in supported }
    }

    private fun copyAssetTree(context: Context, assetPath: String, target: File) {
        val children = context.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            target.parentFile?.let(::ensureDirectory)
            context.assets.open(assetPath).use { input ->
                target.outputStream().buffered().use { output -> input.copyTo(output) }
            }
            return
        }
        ensureDirectory(target)
        children.forEach { child -> copyAssetTree(context, "$assetPath/$child", File(target, child)) }
    }

    private fun ensureDirectory(directory: File) {
        check(directory.isDirectory || directory.mkdirs()) {
            "Unable to create built-in ROM directory: ${directory.absolutePath}"
        }
    }

    private fun isInsideRoot(file: File, canonicalRootPath: String?): Boolean {
        val rootPath = canonicalRootPath ?: return false
        val canonicalPath = runCatching { file.canonicalPath }.getOrNull() ?: return false
        return canonicalPath.startsWith(rootPath)
    }

    private fun relativeSortPath(file: File, root: File): String =
        file.relativeToOrNull(root)?.invariantSeparatorsPath ?: file.name

    private fun relativeFolder(file: File, root: File): String =
        file.parentFile?.relativeToOrNull(root)?.invariantSeparatorsPath.orEmpty()

    private fun mergeIntoLibraryCache(context: Context, root: File) {
        if (!root.isDirectory) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cachedJson = prefs.getString(CACHE_KEY, "[]") ?: "[]"
        val existing = runCatching { JSONArray(cachedJson) }.getOrElse { error ->
            Log.w(TAG, "Malformed ROM library cache; rebuilding built-in entries", error)
            JSONArray()
        }
        val byUri = LinkedHashMap<String, JSONObject>()
        val canonicalRootPath = runCatching { root.canonicalPath + File.separator }.getOrNull()
        for (i in 0 until existing.length()) {
            val item = existing.optJSONObject(i) ?: continue
            val uri = item.optString("u")
            if (uri.isBlank()) continue

            val parsed = runCatching { Uri.parse(uri) }.getOrNull()
            val cachedFile = if (parsed?.scheme == "file") parsed.path?.let(::File) else null
            val isBuiltIn = cachedFile?.let { file ->
                isInsideRoot(file, canonicalRootPath)
            } == true
            if (isBuiltIn) continue

            byUri[uri] = item
        }

        root.walkTopDown()
            .mapNotNull { file ->
                val extension = supportedExtension(file) ?: return@mapNotNull null
                val relativePath = relativeSortPath(file, root)
                ScannedRom(
                    file = file,
                    relativePath = relativePath,
                    sortKey = relativePath.lowercase(Locale.ROOT),
                    extension = extension,
                    folder = relativeFolder(file, root),
                )
            }
            .sortedWith(
                compareBy<ScannedRom> { it.sortKey }
                    .thenBy { it.relativePath }
            )
            .forEach { rom ->
                val file = rom.file
                val uri = Uri.fromFile(file).toString()
                byUri[uri] = JSONObject()
                    .put("u", uri)
                    .put("n", file.name)
                    .put("e", rom.extension)
                    .put("f", if (rom.folder.isBlank()) "Built-in" else "Built-in/${rom.folder}")
            }

        val merged = JSONArray()
        byUri.values.forEach { merged.put(it) }
        val mergedJson = merged.toString()
        if (mergedJson != cachedJson) {
            prefs.edit().putString(CACHE_KEY, mergedJson).apply()
        }
    }
}
