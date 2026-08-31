package com.ric.emuhub

import android.app.Application
import android.widget.Toast
import java.io.File

class EmuHubApp : Application() {
    override fun onCreate() {
        super.onCreate()

        runCatching { File(cacheDir, "ps2roms").deleteRecursively() }

        val trace = getSharedPreferences("ps2_runtime_trace", MODE_PRIVATE)
        if (trace.getBoolean("active", false)) {
            val stage = trace.getString("stage", "unknown") ?: "unknown"
            trace.edit()
                .putString("last_crash_stage", stage)
                .putLong("last_crash_time", System.currentTimeMillis())
                .putBoolean("active", false)
                .commit()
            Toast.makeText(this, "PS2 native crash stage: $stage", Toast.LENGTH_LONG).show()
        }
    }
}
