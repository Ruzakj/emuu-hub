package com.ric.emuhub

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.widget.Toast
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import javax.microedition.util.ContextHolder
import org.acra.ACRA
import org.acra.config.CoreConfigurationBuilder

class EmuHubApp : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)

        // JL-Mod normally performs this work from EmulatorApplication.attachBaseContext().
        // Emu Hub embeds the runtime as an AAR, so initialize the runtime before any
        // Config/MicroActivity static initializer can run.
        runCatching { ContextHolder.init(this) }
        runCatching {
            if (!ACRA.isACRASenderServiceProcess()) {
                ACRA.init(this, CoreConfigurationBuilder().withParallel(false))
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        runCatching { ContextHolder.init(this) }
        runCatching { StoragePaths.ensureLayout(this) }

        installJ2meCrashHandler()
        recoverJ2meCrashTrace()

        // Ps2GameActivity runs in the dedicated :ps2 process. Never clean ps2roms from that
        // process: MainActivity has just copied the selected ISO/CHD there immediately before
        // starting :ps2, so deleting it here causes the intermittent "PS2 ROM tidak ditemukan"
        // bounce back to the library on every cold PS2-process launch.
        val processName = if (Build.VERSION.SDK_INT >= 28) getProcessName() else packageName
        val isPs2Process = processName.endsWith(":ps2")
        if (!isPs2Process) {
            runCatching { File(cacheDir, "ps2roms").deleteRecursively() }
        }

        val trace = getSharedPreferences("ps2_runtime_trace", MODE_PRIVATE)
        if (trace.getBoolean("active", false)) {
            val stage = trace.getString("stage", "unknown") ?: "unknown"
            trace.edit()
                .putString("last_crash_stage", stage)
                .putLong("last_crash_time", System.currentTimeMillis())
                .putBoolean("active", false)
                .commit()
            if (!isPs2Process) {
                Toast.makeText(this, "PS2 native crash stage: $stage", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun j2meLogFile(): File = File(StoragePaths.root(this), "J2ME/crash.txt").apply {
        parentFile?.mkdirs()
    }

    private fun installJ2meCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val trace = getSharedPreferences("j2me_runtime_trace", MODE_PRIVATE)
                if (trace.getBoolean("active", false)) {
                    val stage = trace.getString("stage", "unknown") ?: "unknown"
                    val game = trace.getString("game", "unknown") ?: "unknown"
                    val sw = StringWriter()
                    error.printStackTrace(PrintWriter(sw))
                    j2meLogFile().writeText(
                        buildString {
                            appendLine("EMU HUB J2ME CRASH")
                            appendLine("time=${System.currentTimeMillis()}")
                            appendLine("game=$game")
                            appendLine("stage=$stage")
                            appendLine("thread=${thread.name}")
                            appendLine("exception=${error.javaClass.name}: ${error.message}")
                            appendLine()
                            append(sw.toString())
                        }
                    )
                    trace.edit()
                        .putString("last_crash_stage", stage)
                        .putString("last_crash_game", game)
                        .putLong("last_crash_time", System.currentTimeMillis())
                        .putBoolean("active", false)
                        .commit()
                }
            }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun recoverJ2meCrashTrace() {
        val trace = getSharedPreferences("j2me_runtime_trace", MODE_PRIVATE)
        if (!trace.getBoolean("active", false)) return

        val stage = trace.getString("stage", "unknown") ?: "unknown"
        val game = trace.getString("game", "unknown") ?: "unknown"
        val extra = StringBuilder()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                val am = getSystemService(ActivityManager::class.java)
                val exit = am.getHistoricalProcessExitReasons(packageName, 0, 5).firstOrNull()
                if (exit != null) {
                    extra.appendLine("androidExitReason=${exit.reason}")
                    extra.appendLine("androidExitStatus=${exit.status}")
                    extra.appendLine("androidExitImportance=${exit.importance}")
                    extra.appendLine("androidExitDescription=${exit.description ?: ""}")
                }
            }
        }

        runCatching {
            j2meLogFile().writeText(
                buildString {
                    appendLine("EMU HUB J2ME PROCESS CRASH")
                    appendLine("time=${System.currentTimeMillis()}")
                    appendLine("game=$game")
                    appendLine("stage=$stage")
                    append(extra)
                }
            )
        }

        trace.edit()
            .putString("last_crash_stage", stage)
            .putString("last_crash_game", game)
            .putLong("last_crash_time", System.currentTimeMillis())
            .putBoolean("active", false)
            .commit()

        Toast.makeText(
            this,
            "J2ME crash captured: $stage • log emu-hub/J2ME/crash.txt",
            Toast.LENGTH_LONG
        ).show()
    }
}
