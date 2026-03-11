package me.i996.client.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import me.i996.client.util.Prefs

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            val token = Prefs.getToken(context)
            val server = Prefs.getServer(context)
            if (token.isNotEmpty()) {
                val serviceIntent = TunnelService.startIntent(context, token, server)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
        }
    }
}
