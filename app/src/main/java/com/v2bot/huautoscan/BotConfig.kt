package com.v2bot.huautoscan

import android.content.Context
import android.content.SharedPreferences

object BotConfig {
    private const val PREF_NAME = "hu_auto_scan_bot_prefs"
    private const val KEY_QUEUE = "article_queue"
    private const val KEY_LABELS = "article_labels"
    private const val KEY_TAP_DELAY_MS = "tap_delay_ms"
    private const val KEY_AUTO_RESUME = "auto_resume_next"
    private const val KEY_ARTICLE_PAUSE_SEC = "article_pause_sec"
    private const val KEY_AUTO_SAVE = "auto_save"
    private const val KEY_SOUND_VIBRATE = "sound_vibrate"
    private const val KEY_SYNC_ENABLED = "sync_enabled"
    private const val KEY_SERVER_URL = "server_url"
    private const val KEY_EAN_X = "ean_target_x"
    private const val KEY_EAN_Y = "ean_target_y"
    private const val KEY_SAVE_X = "save_target_x"
    private const val KEY_SAVE_Y = "save_target_y"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun getQueueRaw(context: Context): String =
        prefs(context).getString(KEY_QUEUE, "10, 15, 5") ?: "10, 15, 5"

    fun setQueueRaw(context: Context, raw: String) {
        prefs(context).edit().putString(KEY_QUEUE, raw.trim()).apply()
    }

    fun parseQueue(raw: String): List<Int> {
        return raw.split(",", " ", "\n", ";", "+")
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it > 0 }
    }

    fun getQueueList(context: Context): List<Int> = parseQueue(getQueueRaw(context))

    fun getLabelsRaw(context: Context): String =
        prefs(context).getString(KEY_LABELS, "") ?: ""

    fun setLabelsRaw(context: Context, raw: String) {
        prefs(context).edit().putString(KEY_LABELS, raw).apply()
    }

    fun getLabelsList(context: Context): List<String> {
        val raw = getLabelsRaw(context)
        if (raw.isBlank()) return emptyList()
        return raw.split("||").map { it.trim() }
    }

    fun getTapDelayMs(context: Context): Long =
        prefs(context).getLong(KEY_TAP_DELAY_MS, 2500L).coerceIn(1000L, 6000L)

    fun setTapDelayMs(context: Context, ms: Long) {
        prefs(context).edit().putLong(KEY_TAP_DELAY_MS, ms.coerceIn(1000L, 6000L)).apply()
    }

    fun isAutoResumeNext(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_RESUME, true)

    fun setAutoResumeNext(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_RESUME, enabled).apply()
    }

    fun getArticlePauseSec(context: Context): Int =
        prefs(context).getInt(KEY_ARTICLE_PAUSE_SEC, 3).coerceIn(2, 15)

    fun setArticlePauseSec(context: Context, sec: Int) {
        prefs(context).edit().putInt(KEY_ARTICLE_PAUSE_SEC, sec.coerceIn(2, 15)).apply()
    }

    fun isAutoSave(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_SAVE, false)

    fun setAutoSave(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_SAVE, enabled).apply()
    }

    fun isSoundVibrate(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SOUND_VIBRATE, true)

    fun setSoundVibrate(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SOUND_VIBRATE, enabled).apply()
    }

    fun isSyncEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SYNC_ENABLED, true)

    fun setSyncEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SYNC_ENABLED, enabled).apply()
    }

    fun getServerUrl(context: Context): String =
        prefs(context).getString(KEY_SERVER_URL, "http://192.168.1.10:8765")
            ?: "http://192.168.1.10:8765"

    fun setServerUrl(context: Context, url: String) {
        val cleaned = url.trim().trimEnd('/')
        prefs(context).edit().putString(KEY_SERVER_URL, cleaned).apply()
    }

    fun getEanPos(context: Context, defaultX: Int, defaultY: Int): Pair<Int, Int> {
        val p = prefs(context)
        return Pair(p.getInt(KEY_EAN_X, defaultX), p.getInt(KEY_EAN_Y, defaultY))
    }

    fun setEanPos(context: Context, x: Int, y: Int) {
        prefs(context).edit().putInt(KEY_EAN_X, x).putInt(KEY_EAN_Y, y).apply()
    }

    fun getSavePos(context: Context, defaultX: Int, defaultY: Int): Pair<Int, Int> {
        val p = prefs(context)
        return Pair(p.getInt(KEY_SAVE_X, defaultX), p.getInt(KEY_SAVE_Y, defaultY))
    }

    fun setSavePos(context: Context, x: Int, y: Int) {
        prefs(context).edit().putInt(KEY_SAVE_X, x).putInt(KEY_SAVE_Y, y).apply()
    }
}
