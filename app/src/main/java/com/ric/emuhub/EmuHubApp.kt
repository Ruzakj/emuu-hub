package com.ric.emuhub

import android.app.Application
import android.os.Build
import android.widget.Toast
import java.io.File

class EmuHubApp : Application() {
    override fun onCreate() {
        super.onCreate()

        runCatching { StoragePaths.ensureLayout(this) }

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
}
