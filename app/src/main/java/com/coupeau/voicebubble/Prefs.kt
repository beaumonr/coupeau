package com.coupeau.voicebubble

import android.content.Context
import android.content.SharedPreferences

/** All settings live here so the bubble, the tile, the notification and the settings screen agree. */
object Prefs {
    const val FILE = "voice_bubble"

    const val KEY_ENABLED = "enabled"
    const val KEY_SIZE_DP = "size_dp"
    const val KEY_SNAP = "snap_to_button"
    const val KEY_APPS = "apps"
    const val KEY_MUTE_BEEPS = "mute_beeps"
    private const val POS_PREFIX = "pos_"

    const val DEFAULT_SIZE_DP = 60
    const val MIN_SIZE_DP = 36
    const val MAX_SIZE_DP = 120

    /** Apps offered as tick boxes in settings: package name to label. */
    val KNOWN_APPS = linkedMapOf(
        "com.whatsapp" to "WhatsApp",
        "com.whatsapp.w4b" to "WhatsApp Business",
        "com.google.android.apps.translate" to "Google Translate",
        "com.google.android.apps.messaging" to "Google Messages",
        "com.samsung.android.messaging" to "Samsung Messages",
        "com.google.android.gm" to "Gmail",
    )

    private val DEFAULT_APPS = KNOWN_APPS.keys.toSet()

    fun get(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun enabled(ctx: Context) = get(ctx).getBoolean(KEY_ENABLED, true)
    fun setEnabled(ctx: Context, on: Boolean) = get(ctx).edit().putBoolean(KEY_ENABLED, on).apply()

    fun sizeDp(ctx: Context) = get(ctx).getInt(KEY_SIZE_DP, DEFAULT_SIZE_DP)
    fun setSizeDp(ctx: Context, dp: Int) = get(ctx).edit().putInt(KEY_SIZE_DP, dp).apply()

    fun snap(ctx: Context) = get(ctx).getBoolean(KEY_SNAP, true)
    fun setSnap(ctx: Context, on: Boolean) = get(ctx).edit().putBoolean(KEY_SNAP, on).apply()

    fun muteBeeps(ctx: Context) = get(ctx).getBoolean(KEY_MUTE_BEEPS, true)
    fun setMuteBeeps(ctx: Context, on: Boolean) = get(ctx).edit().putBoolean(KEY_MUTE_BEEPS, on).apply()

    fun apps(ctx: Context): Set<String> = get(ctx).getStringSet(KEY_APPS, null)?.toSet() ?: DEFAULT_APPS

    fun setApps(ctx: Context, apps: Set<String>) =
        get(ctx).edit().putStringSet(KEY_APPS, HashSet(apps)).apply()

    /** A position the user dragged the bubble to in one app, or null to snap automatically. */
    fun manualPos(ctx: Context, pkg: String): Pair<Int, Int>? {
        val p = get(ctx)
        if (!p.contains(POS_PREFIX + pkg + "_x")) return null
        return p.getInt(POS_PREFIX + pkg + "_x", 0) to p.getInt(POS_PREFIX + pkg + "_y", 0)
    }

    fun setManualPos(ctx: Context, pkg: String, x: Int, y: Int) =
        get(ctx).edit().putInt(POS_PREFIX + pkg + "_x", x).putInt(POS_PREFIX + pkg + "_y", y).apply()

    fun clearManualPos(ctx: Context, pkg: String) =
        get(ctx).edit().remove(POS_PREFIX + pkg + "_x").remove(POS_PREFIX + pkg + "_y").apply()

    fun clearAllPositions(ctx: Context) {
        val p = get(ctx)
        val e = p.edit()
        p.all.keys.filter { it.startsWith(POS_PREFIX) }.forEach { e.remove(it) }
        e.apply()
    }
}
