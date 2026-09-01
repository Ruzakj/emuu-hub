package com.ric.emuhub

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/** Requests access once so Emu Hub can keep emulator data in /storage/emulated/0/emu-hub. */
class StoragePermissionActivity : Activity() {
    private var requested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!StoragePaths.needsSharedRootPermission()) {
            openHub()
            return
        }
        requested = true
        Toast.makeText(this, "Izinkan akses semua file agar save, state, memory card, dan data emulator tersimpan di folder emu-hub.", Toast.LENGTH_LONG).show()
        runCatching { startActivity(StoragePaths.permissionIntent(this)) }
            .onFailure { openHub() }
    }

    override fun onResume() {
        super.onResume()
        if (requested && !isFinishing) {
            requested = false
            StoragePaths.ensureLayout(this)
            openHub()
        }
    }

    private fun openHub() {
        StoragePaths.ensureLayout(this)
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
