package com.ric.emuhub

import android.content.Context
import java.io.File
import java.util.Properties

data class Ps2Profile(
    val preset: String,
    val upscale: Float,
    val eeRate: Int,
    val eeSkip: Int,
    val mtvu: Boolean,
    val affinity: Int
)

/**
 * PS2 runs in the isolated :ps2 Android process. Do not use a cached
 * SharedPreferences instance for live tuning: the main process and :ps2 can
 * otherwise observe different cached values. A tiny properties file in the
 * app data directory is read fresh every time a PS2 VM starts.
 */
object Ps2Settings {
    private const val FILE_NAME = "ps2-manual-settings.properties"
    private const val TARGET_UPSCALE = 2.0f

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    fun load(context: Context): Ps2Profile {
        val props = Properties()
        runCatching {
            val f = file(context)
            if (f.isFile) f.inputStream().buffered().use { props.load(it) }
        }
        return Ps2Profile(
            preset = props.getProperty("preset", "Auto Z9x"),
            upscale = props.getProperty("upscale", TARGET_UPSCALE.toString()).toFloatOrNull()?.coerceIn(1f, 3f) ?: TARGET_UPSCALE,
            eeRate = props.getProperty("eeRate", "-2").toIntOrNull()?.coerceIn(-3, 0) ?: -2,
            eeSkip = props.getProperty("eeSkip", "0").toIntOrNull()?.coerceIn(0, 2) ?: 0,
            mtvu = props.getProperty("mtvu", "true").toBooleanStrictOrNull() ?: true,
            affinity = if (props.getProperty("affinity", "0").toIntOrNull() == 7) 7 else 0
        )
    }

    fun save(context: Context, v: Ps2Profile) {
        val props = Properties().apply {
            setProperty("preset", v.preset)
            setProperty("upscale", v.upscale.toString())
            setProperty("eeRate", v.eeRate.toString())
            setProperty("eeSkip", v.eeSkip.toString())
            setProperty("mtvu", v.mtvu.toString())
            setProperty("affinity", v.affinity.toString())
        }
        val target = file(context)
        val tmp = File(target.parentFile, "$FILE_NAME.tmp")
        runCatching {
            target.parentFile?.mkdirs()
            tmp.outputStream().buffered().use { props.store(it, "Emu Hub PS2 manual settings") }
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        }.getOrThrow()
    }

    // All automatic Z9x profiles stay at 2x. Performance is gained from CPU/VU
    // scheduling and EE tuning instead of silently reducing image resolution.
    fun preset(name: String): Ps2Profile = when (name) {
        "Balanced" -> Ps2Profile(name, TARGET_UPSCALE, -1, 0, true, 0)
        "Performance" -> Ps2Profile(name, TARGET_UPSCALE, -2, 0, true, 0)
        "Max Performance" -> Ps2Profile(name, TARGET_UPSCALE, -3, 0, true, 0)
        else -> Ps2Profile("Auto Z9x", TARGET_UPSCALE, -2, 0, true, 0)
    }
}
