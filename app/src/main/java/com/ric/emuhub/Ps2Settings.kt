package com.ric.emuhub

import android.content.Context

data class Ps2Profile(
    val preset: String,
    val upscale: Float,
    val eeRate: Int,
    val eeSkip: Int,
    val mtvu: Boolean,
    val affinity: Int
)

object Ps2Settings {
    private const val PREFS = "ps2_manual_settings"

    fun load(context: Context): Ps2Profile {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Ps2Profile(
            p.getString("preset", "Auto Z9x") ?: "Auto Z9x",
            p.getFloat("upscale", 2.0f),
            p.getInt("eeRate", -2),
            p.getInt("eeSkip", 0),
            p.getBoolean("mtvu", true),
            p.getInt("affinity", 0)
        )
    }

    fun save(context: Context, v: Ps2Profile) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("preset", v.preset).putFloat("upscale", v.upscale)
            .putInt("eeRate", v.eeRate).putInt("eeSkip", v.eeSkip)
            .putBoolean("mtvu", v.mtvu).putInt("affinity", v.affinity).apply()
    }

    fun preset(name: String): Ps2Profile = when (name) {
        "Balanced" -> Ps2Profile(name, 2f, -1, 0, true, 0)
        "Performance" -> Ps2Profile(name, 2f, -2, 0, true, 0)
        "Max Performance" -> Ps2Profile(name, 1.5f, -3, 0, true, 0)
        else -> Ps2Profile("Auto Z9x", 2f, -2, 0, true, 0)
    }
}
