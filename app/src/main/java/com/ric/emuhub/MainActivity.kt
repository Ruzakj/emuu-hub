package com.ric.emuhub

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private val openRom = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        findViewById<TextView>(1001).text = "Selected ROM\n${uri.lastPathSegment ?: uri}"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }
        val title = TextView(this).apply { text = "EMU HUB"; textSize = 32f; gravity = Gravity.CENTER }
        val status = TextView(this).apply { id = 1001; text = "Universal offline emulator hub\nMVP bootstrap"; textSize = 16f; gravity = Gravity.CENTER; setPadding(0,32,0,32) }
        val picker = Button(this).apply {
            text = "SELECT ROM"
            setOnClickListener { openRom.launch(arrayOf("application/octet-stream", "*/*")) }
        }
        root.addView(title)
        root.addView(status)
        root.addView(picker)
        setContentView(root)
    }
}
