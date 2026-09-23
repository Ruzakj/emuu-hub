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
            runCatching { BuiltinRomManager.install(this) }
            EnginePackManager.bootstrapAsync(this)
            val storageInit = Thread({
                runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
                runCatching { StoragePaths.ensureLayout(applicationContext) }
            }, "emuhub-storage-init")
            storageInit.isDaemon = true
            storageInit.start()
            StorageMaintenance.runAsync(this)
        } else if (!isPs2Process) {
            Thread({ runCatching { File(cacheDir, "ps2roms").deleteRecursively() } }, "emuhub-cache-clean").start()
        }

        val coreTrace = getSharedPreferences("core_runtime_trace", MODE_PRIVATE)
        if (coreTrace.getBoolean("active", false) && isMainProcess) {
            val stage = coreTrace.getString("stage", "unknown") ?: "unknown"
            val coreId = coreTrace.getString("core", "unknown") ?: "unknown"
            val game = coreTrace.getString("game", "unknown") ?: "unknown"
            coreTrace.edit().putBoolean("active", false).putString("last_crash_stage", stage).apply()
            Toast.makeText(this, "Core crash: $coreId • $stage • $game. Log: emu-hub/CORE/core-runtime.log", Toast.LENGTH_LONG).show()
        }

        installRuntimeCrashHandler()
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

    private fun installRuntimeCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val isJ2meProcess = currentProcessName().endsWith(":j2me")
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                val log = File(StoragePaths.root(this), "CORE/runtime-crash.log").apply { parentFile?.mkdirs() }
                log.appendText("\n--- ${System.currentTimeMillis()} ${thread.name} ---\n${sw}\n")
                if (isJ2meProcess) {
                    j2meLogFile().appendText("\n--- runtime ${System.currentTimeMillis()} ${thread.name} ---\n${sw}\n")
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
        val at = trace.getLong("updated_at", 0L)
        runCatching {
            j2meLogFile().appendText("\nRecovered interrupted J2ME session: stage=$stage game=$game updated_at=$at\n")
        }
        trace.edit().putBoolean("active", false).putString("last_crash_stage", stage).apply()
    }
}
