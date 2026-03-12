package me.i996.client.util

import android.content.Context

object Prefs {
    private const val NAME = "i996_prefs"
    private const val KEY_TOKEN = "token"
    private const val KEY_SERVER = "server"
    private const val DEFAULT_SERVER = "192.168.1.213:8225"  // dedicated mobile port

    fun getToken(ctx: Context): String =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getString(KEY_TOKEN, "") ?: ""

    fun saveToken(ctx: Context, token: String) =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_TOKEN, token).apply()

    fun getServer(ctx: Context): String =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getString(KEY_SERVER, DEFAULT_SERVER) ?: DEFAULT_SERVER

    fun saveServer(ctx: Context, server: String) =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_SERVER, server).apply()
}
