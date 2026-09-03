package com.ric.emuhub

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
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
        runCatching { ContextHolder.init(this) }
        val process = if (Build.VERSION.SDK_INT >= 28) getProcessName() else base.packageName
        if (process.endsWith(":j2me")) {
            runCatching {
                if (!ACRA.isACRASenderServiceProcess()) ACRA.init(this, CoreConfigurationBuilder().withParallel(false))
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        runCatching { ContextHolder.init(this) }
        val processName = currentProcessName()
        val isMainProcess = processName == packageName
        val isPs2Process = processName.endsWith(":ps2")
        val isJ2meProcess = processName.endsWith(":j2me")

        if (isMainProcess) {
            EnginePackManager.bootstrapAsync(this)
            Thread({ runCatching { StoragePaths.ensureLayout(applicationContext) } }, "emuhub-storage-init").start()
            StorageMaintenance.runAsync(this)
        } else if (!isPs2Process) {
            Thread({ runCatching { File(cacheDir, "ps2roms").deleteRecursively() } }, "emuhub-cache-clean").start()
        }

        installJ2meCrashHandler()
        if (isMainProcess || isJ2meProcess) recoverJ2meCrashTrace()

        val trace = getSharedPreferences("ps2_runtime_trace", MODE_PRIVATE)
        if (trace.getBoolean("active", false)) {
            val stage = trace.getString("stage", "unknown") ?: "unknown"
            trace.edit().putString("last_crash_stage", stage).putLong("last_crash_time", System.currentTimeMillis()).putBoolean("active", false).apply()
            if (!isPs2Process) Toast.makeText(this, "PS2 native crash stage: $stage", Toast.LENGTH_LONG).show()
        }
    }

    private fun currentProcessName(): String {
        if (Build.VERSION.SDK_INT >= 28) return getProcessName()
        return runCatching {
            val pid = Process.myPid()
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName
        }.getOrNull() ?: packageName
    }

    private fun j2meLogFile(): File = File(StoragePaths.root(this), "J2ME/crash.txt").apply { parentFile?.mkdirs() }

    private fun installJ2meCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val isJ2meProcess = currentProcessName().endsWith(":j2me")
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val trace = getSharedPreferences("j2me_runtime_trace", MODE_PRIVATE)
                if (trace.getBoolean("active", false) || isJ2meProcess) {
                    val stage = trace.getString("stage", "runtime") ?: "runtime"
                    val game = trace.getString("game", "unknown") ?: "unknown"
                    val sw = StringWriter(); error.printStackTrace(PrintWriter(sw))
                    j2meLogFile().writeText(buildString {
                        appendLine("EMU HUB J2ME CRASH"); appendLine("time=${System.currentTimeMillis()}"); appendLine("game=$game")
                        appendLine("stage=$stage"); appendLine("process=${currentProcessName()}"); appendLine("thread=${thread.name}")
                        appendLine("exception=${error.javaClass.name}: ${error.message}"); appendLine(); append(sw.toString())
                    })
                    trace.edit().putString("last_crash_stage", stage).putString("last_crash_game", game).putLong("last_crash_time", System.currentTimeMillis()).putBoolean("active", false).commit()
                }
            }
            if (isJ2meProcess) { Process.killProcess(Process.myPid()); return@setDefaultUncaughtExceptionHandler }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun recoverJ2meCrashTrace() {
        val trace = getSharedPreferences("j2me_runtime_trace", MODE_PRIVATE)
        if (!trace.getBoolean("active", false)) return
        val stage = trace.getString("stage", "unknown") ?: "unknown"
        val game = trace.getString("game", "unknown") ?: "unknown"
        val extra = StringBuilder()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) runCatching {
            val exit = getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(packageName, 0, 5).firstOrNull()
            if (exit != null) { extra.appendLine("androidExitReason=${exit.reason}"); extra.appendLine("androidExitStatus=${exit.status}"); extra.appendLine("androidExitImportance=${exit.importance}"); extra.appendLine("androidExitDescription=${exit.description ?: ""}") }
        }
        runCatching { j2meLogFile().writeText(buildString { appendLine("EMU HUB J2ME PROCESS CRASH"); appendLine("time=${System.currentTimeMillis()}"); appendLine("game=$game"); appendLine("stage=$stage"); append(extra) }) }
        trace.edit().putString("last_crash_stage", stage).putString("last_crash_game", game).putLong("last_crash_time", System.currentTimeMillis()).putBoolean("active", false).commit()
        if (!currentProcessName().endsWith(":j2me")) Toast.makeText(this, "J2ME crash captured: $stage • log emu-hub/J2ME/crash.txt", Toast.LENGTH_LONG).show()
    }
}
