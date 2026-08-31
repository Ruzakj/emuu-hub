package com.ric.emuhub

import android.app.Application
import android.widget.Toast
import java.io.File

class EmuHubApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // PS2 disc images are disposable working copies. A native SIGSEGV/abort can kill
        // the process before Activity.onDestroy(), so clean them at every cold start.
        runCatching { File(cacheDir, "ps2roms").deleteRecursively() }

        // Native crashes cannot be caught by Kotlin. Ps2GameActivity commits a breadcrumb
        // immediately before each dangerous JNI call; after a process restart this tells us
        // exactly which ARMSX2 stage killed the process.
        val trace = getSharedPreferences("ps2_runtime_trace", MODE_PRIVATE)
        if (trace.getBoolean("active", false)) {
            val stage = trace.getString("stage", "unknown") ?: "unknown"
            Toast.makeText(this, "PS2 native crash stage: $stage • temp cache cleaned", Toast.LENGTH_LONG).show()
            trace.edit().putBoolean("active", false).apply()
        }
    }
}
