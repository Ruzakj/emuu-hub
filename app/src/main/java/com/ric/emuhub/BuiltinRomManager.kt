package com.ric.emuhub

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object BuiltinRomManager {
    private const val TAG = "BuiltinRomManager"
    private const val PREFS = "emuhub_library"
    private const val CACHE_KEY = "library_cache_v2"
    private const val ASSET_ROOT = "builtin-roms"
    private const val MARKER = ".builtin_roms_v1"
    private val supported = setOf("gb", "gbc", "gba", "nes", "sfc", "smc")

    fun install(context: Context) {
        val targetRoot = File(context.filesDir, ASSET_ROOT)
        val marker = File(targetRoot, MARKER)
        if (!marker.isFile) {
            val installed = runCatching {
                targetRoot.mkdirs()
                copyAssetTree(context, ASSET_ROOT, targetRoot)
                marker.writeText("1")
            }.onFailure { error ->
                Log.w(TAG, "Unable to install built-in ROM starter pack", error)
            }.isSuccess
            if (!installed) return
        }
        runCatching { mergeIntoLibraryCache(context, targetRoot) }
            .onFailure { error -> Log.w(TAG, "Unable to merge built-in ROMs into library cache", error) }
    }

    private fun copyAssetTree(context: Context, assetPath: String, target: File) {
        val children = context.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                target.outputStream().buffered().use { output -> input.copyTo(output) }
            }
            return
        }
        target.mkdirs()
        children.forEach { child -> copyAssetTree(context, "$assetPath/$child", File(target, child)) }
    }

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
        val absoluteRootPath = root.absolutePath + File.separator
        for (i in 0 until existing.length()) {
            val item = existing.optJSONObject(i) ?: continue
            val uri = item.optString("u")
            if (uri.isBlank()) continue

            val parsed = runCatching { Uri.parse(uri) }.getOrNull()
            val cachedFile = if (parsed?.scheme == "file") parsed.path?.let(::File) else null
            val isBuiltIn = cachedFile?.let { file ->
                val canonicalPath = runCatching { file.canonicalPath }.getOrNull()
                if (canonicalPath != null && canonicalRootPath != null) {
                    canonicalPath.startsWith(canonicalRootPath)
                } else {
                    file.absolutePath.startsWith(absoluteRootPath)
                }
            } == true
            if (isBuiltIn && cachedFile?.isFile != true) continue

            byUri[uri] = item
        }

        val builtInRoms = root.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in supported }
            .toList()
            .sortedBy { file ->
                file.relativeToOrNull(root)?.invariantSeparatorsPath?.lowercase() ?: file.name.lowercase()
            }
        builtInRoms.forEach { file ->
            val uri = Uri.fromFile(file).toString()
            val folder = file.parentFile?.relativeToOrNull(root)?.invariantSeparatorsPath.orEmpty()
            byUri[uri] = JSONObject()
                .put("u", uri)
                .put("n", file.name)
                .put("e", file.extension.lowercase())
                .put("f", if (folder.isBlank()) "Built-in" else "Built-in/$folder")
        }

        val merged = JSONArray()
        byUri.values.forEach { merged.put(it) }
        val mergedJson = merged.toString()
        if (mergedJson != cachedJson) {
            prefs.edit().putString(CACHE_KEY, mergedJson).apply()
        }
    }
}
