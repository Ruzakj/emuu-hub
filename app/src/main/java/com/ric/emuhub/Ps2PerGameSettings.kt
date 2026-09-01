package com.ric.emuhub

import android.content.Context
import java.io.File
import java.util.Properties

data class Ps2PerGameProfile(
    val profile: Ps2Profile,
    val speedPercent: Int
)

/**
 * Per-game PS2 overrides keyed by the direct ROM path. The file is read fresh
 * in the :ps2 process so a profile saved from the in-game quick menu is the
 * exact profile used on the next boot of that title.
 */
object Ps2PerGameSettings {
    private const val FILE_NAME = "ps2-per-game.properties"

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)
    private fun key(path: String): String = path.hashCode().toUInt().toString(16)

    fun load(context: Context, romPath: String): Ps2PerGameProfile? {
        if (romPath.isBlank()) return null
        val props = Properties()
        runCatching {
            val f = file(context)
            if (!f.isFile) return null
            f.inputStream().buffered().use { props.load(it) }
        }.getOrElse { return null }
        val prefix = key(romPath)
        if (props.getProperty("$prefix.enabled") != "true") return null
        val profile = Ps2Profile(
            preset = props.getProperty("$prefix.preset", "Per-game"),
            upscale = props.getProperty("$prefix.upscale", "2.0").toFloatOrNull()?.coerceIn(1f, 3f) ?: 2f,
            eeRate = props.getProperty("$prefix.eeRate", "-2").toIntOrNull()?.coerceIn(-3, 0) ?: -2,
            eeSkip = props.getProperty("$prefix.eeSkip", "0").toIntOrNull()?.coerceIn(0, 2) ?: 0,
            mtvu = props.getProperty("$prefix.mtvu", "true").toBooleanStrictOrNull() ?: true,
            affinity = if (props.getProperty("$prefix.affinity", "0").toIntOrNull() == 7) 7 else 0
        )
        val speed = props.getProperty("$prefix.speed", "100").toIntOrNull()?.coerceIn(25, 200) ?: 100
        return Ps2PerGameProfile(profile, speed)
    }

    fun save(context: Context, romPath: String, value: Ps2PerGameProfile) {
        if (romPath.isBlank()) return
        val props = Properties()
        val target = file(context)
        runCatching { if (target.isFile) target.inputStream().buffered().use { props.load(it) } }
        val prefix = key(romPath)
        props.setProperty("$prefix.enabled", "true")
        props.setProperty("$prefix.path", romPath)
        props.setProperty("$prefix.preset", value.profile.preset)
        props.setProperty("$prefix.upscale", value.profile.upscale.toString())
        props.setProperty("$prefix.eeRate", value.profile.eeRate.toString())
        props.setProperty("$prefix.eeSkip", value.profile.eeSkip.toString())
        props.setProperty("$prefix.mtvu", value.profile.mtvu.toString())
        props.setProperty("$prefix.affinity", value.profile.affinity.toString())
        props.setProperty("$prefix.speed", value.speedPercent.coerceIn(25, 200).toString())
        writeAtomic(target, props)
    }

    fun clear(context: Context, romPath: String) {
        if (romPath.isBlank()) return
        val target = file(context)
        if (!target.isFile) return
        val props = Properties()
        runCatching { target.inputStream().buffered().use { props.load(it) } }.getOrElse { return }
        val prefix = key(romPath)
        props.keys.map { it.toString() }.filter { it.startsWith("$prefix.") }.forEach { props.remove(it) }
        writeAtomic(target, props)
    }

    private fun writeAtomic(target: File, props: Properties) {
        val tmp = File(target.parentFile, "$FILE_NAME.tmp")
        target.parentFile?.mkdirs()
        tmp.outputStream().buffered().use { props.store(it, "Emu Hub PS2 per-game settings") }
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }
}
