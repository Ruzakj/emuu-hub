package com.ric.emuhub

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

/** Requests shared-storage access once, while keeping cold start visibly responsive. */
class StoragePermissionActivity : Activity() {
    private var requested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showStartupUi()

        if (!StoragePaths.needsSharedRootPermission()) {
            // Do not run migration/copy work on the launcher thread. MainActivity can render
            // immediately while the idempotent storage layout is prepared in the background.
            Thread({ runCatching { StoragePaths.ensureLayout(applicationContext) } }, "emuhub-storage-init").start()
            window.decorView.post { openHub() }
            return
        }

        requested = true
        Toast.makeText(
            this,
            "Izinkan akses semua file agar save, state, memory card, dan data emulator tersimpan di folder emu-hub.",
            Toast.LENGTH_LONG
        ).show()
        runCatching { startActivity(StoragePaths.permissionIntent(this)) }
            .onFailure { openHub() }
    }

    override fun onResume() {
        super.onResume()
        if (requested && !isFinishing) {
            requested = false
            Thread({ runCatching { StoragePaths.ensureLayout(applicationContext) } }, "emuhub-storage-init").start()
            openHub()
        }
    }

    private fun showStartupUi() {
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            setBackgroundColor(0xFF030406.toInt())
        }
        root.addView(TextView(this).apply {
            text = "EMU HUB"
            textSize = 28f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            letterSpacing = 0.08f
        })
        root.addView(TextView(this).apply {
            text = "STARTING CONSOLE SYSTEM"
            textSize = 10f
            setTextColor(0xFF7E8A99.toInt())
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            letterSpacing = 0.14f
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = 10 })
        root.addView(ProgressBar(this).apply { isIndeterminate = true }, LinearLayout.LayoutParams(42, 42).apply { topMargin = 28 })
        setContentView(root)
    }

    private fun openHub() {
        if (isFinishing) return
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION))
        overridePendingTransition(0, 0)
        finish()
    }
}
