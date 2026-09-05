package com.ric.emuhub

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object BuiltinRomManager {
    private const val PREFS = "emuhub_library"
    private const val CACHE_KEY = "library_cache_v2"
    private const val ASSET_ROOT = "builtin-roms"
    private const val MARKER = ".builtin_roms_v1"
    private val supported = setOf("gb", "gbc", "gba", "nes", "sfc", "smc")

    fun install(context: Context) {
        val targetRoot = File(context.filesDir, ASSET_ROOT)
        val marker = File(targetRoot, MARKER)
        if (!marker.isFile) {
            targetRoot.mkdirs()
            copyAssetTree(context, ASSET_ROOT, targetRoot)
            marker.writeText("1")
        }
        mergeIntoLibraryCache(context, targetRoot)
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
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = runCatching { JSONArray(prefs.getString(CACHE_KEY, "[]")) }.getOrElse { JSONArray() }
        val byUri = LinkedHashMap<String, JSONObject>()
        for (i in 0 until existing.length()) {
            val item = existing.optJSONObject(i) ?: continue
            val uri = item.optString("u")
            if (uri.isNotBlank()) byUri[uri] = item
        }

        root.walkTopDown().filter { it.isFile && it.extension.lowercase() in supported }.forEach { file ->
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
        prefs.edit().putString(CACHE_KEY, merged.toString()).commit()
    }
}
