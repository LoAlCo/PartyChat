package com.partychat.app

import android.content.Context

object Prefs {
    // Starting page. Use the menu's "Set this page as home" once you find the
    // party chat page you normally use in your phone's browser.
    const val DEFAULT_HOME = "https://www.xbox.com/play"

    private fun p(c: Context) = c.getSharedPreferences("partychat", Context.MODE_PRIVATE)

    fun home(c: Context): String = p(c).getString("home_v2", DEFAULT_HOME) ?: DEFAULT_HOME
    fun setHome(c: Context, url: String) = p(c).edit().putString("home_v2", url).apply()

    fun desktopMode(c: Context) = p(c).getBoolean("desktop", false)
    fun setDesktopMode(c: Context, on: Boolean) = p(c).edit().putBoolean("desktop", on).apply()

    fun speakerFallback(c: Context) = p(c).getBoolean("speaker", true)
    fun setSpeakerFallback(c: Context, on: Boolean) = p(c).edit().putBoolean("speaker", on).apply()
}
