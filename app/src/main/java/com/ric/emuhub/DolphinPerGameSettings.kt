package com.ric.emuhub

import android.content.Context
import java.io.File
import java.util.Properties

data class DolphinPerGameProfile(
    val cpuClockPercent: Int = 100,
    val presentDivisor: Int = 2,
    val audioBufferScale: Int = 1,
    val controllerDevice: Int = 0x401
)

/** Per-game Dolphin performance overrides. Applied on the next boot of a title. */
object DolphinPerGameSettings {
    private const val FILE_NAME = "dolphin-per-game.properties"

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)
    private fun key(path: String): String = path.hashCode().toUInt().toString(16)

    fun load(context: Context, romPath: String): DolphinPerGameProfile {
        if (romPath.isBlank()) return DolphinPerGameProfile()
        val props = Properties()
        runCatching {
            val f = file(context)
            if (f.isFile) f.inputStream().buffered().use { props.load(it) }
        }
        val prefix = key(romPath)
        return DolphinPerGameProfile(
            cpuClockPercent = props.getProperty("$prefix.cpuClock", "100").toIntOrNull()?.coerceIn(60, 100) ?: 100,
            presentDivisor = props.getProperty("$prefix.presentDivisor", "2").toIntOrNull()?.coerceIn(1, 3) ?: 2,
            audioBufferScale = props.getProperty("$prefix.audioBufferScale", "1").toIntOrNull()?.coerceIn(1, 3) ?: 1,
            controllerDevice = props.getProperty("$prefix.controller", "1025").toIntOrNull()?.takeIf { it in setOf(0x201,0x301,0x401,0x501,0x601) } ?: 0x401
        )
    }

    fun save(context: Context, romPath: String, value: DolphinPerGameProfile) {
        if (romPath.isBlank()) return
        val target = file(context)
        val props = Properties()
        runCatching { if (target.isFile) target.inputStream().buffered().use { props.load(it) } }
        val prefix = key(romPath)
        props.setProperty("$prefix.path", romPath)
        props.setProperty("$prefix.cpuClock", value.cpuClockPercent.coerceIn(60,100).toString())
        props.setProperty("$prefix.presentDivisor", value.presentDivisor.coerceIn(1,3).toString())
        props.setProperty("$prefix.audioBufferScale", value.audioBufferScale.coerceIn(1,3).toString())
        props.setProperty("$prefix.controller", value.controllerDevice.toString())
        writeAtomic(target, props)
    }

    fun reset(context: Context, romPath: String) {
        val target = file(context)
        if (!target.isFile || romPath.isBlank()) return
        val props = Properties()
        runCatching { target.inputStream().buffered().use { props.load(it) } }.getOrElse { return }
        val prefix = key(romPath)
        props.keys.map { it.toString() }.filter { it.startsWith("$prefix.") }.forEach { props.remove(it) }
        writeAtomic(target, props)
    }

    private fun writeAtomic(target: File, props: Properties) {
        val tmp = File(target.parentFile, "$FILE_NAME.tmp")
        target.parentFile?.mkdirs()
        tmp.outputStream().buffered().use { props.store(it, "Emu Hub Dolphin per-game settings") }
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }
}
