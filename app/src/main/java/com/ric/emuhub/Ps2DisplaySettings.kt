package com.ric.emuhub

import android.content.Context
import java.io.File
import java.util.Properties

/** Cross-process PS2 display settings shared by the main app and isolated :ps2 runtime. */
object Ps2DisplaySettings {
    private const val FILE_NAME = "ps2-display-settings.properties"
    val MODES = linkedMapOf(
        "FULL SCREEN / STRETCH" to "Stretch",
        "WIDESCREEN 16:9" to "16:9",
        "ORIGINAL 4:3" to "4:3",
        "AUTO 4:3 / 3:2" to "Auto 4:3/3:2"
    )

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    fun load(context: Context): String {
        val props = Properties()
        runCatching { file(context).takeIf { it.isFile }?.inputStream()?.buffered()?.use { props.load(it) } }
        val value = props.getProperty("aspectRatio", "Stretch")
        return if (value in MODES.values) value else "Stretch"
    }

    fun label(context: Context): String {
        val value = load(context)
        return MODES.entries.firstOrNull { it.value == value }?.key ?: "FULL SCREEN / STRETCH"
    }

    fun save(context: Context, value: String) {
        require(value in MODES.values) { "Unsupported PS2 display mode" }
        val target = file(context)
        val tmp = File(target.parentFile, "$FILE_NAME.tmp")
        val props = Properties().apply { setProperty("aspectRatio", value) }
        target.parentFile?.mkdirs()
        tmp.outputStream().buffered().use { props.store(it, "Emu Hub PS2 display settings") }
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }
}
