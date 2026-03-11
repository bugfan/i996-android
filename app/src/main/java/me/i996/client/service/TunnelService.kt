package me.i996.client.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import me.i996.client.R
import me.i996.client.mux.AuthResult
import me.i996.client.mux.TunnelClient
import me.i996.client.ui.MainActivity
import me.i996.client.util.Prefs
import me.i996.client.util.LogBuffer

private const val TAG = "TunnelService"
private const val NOTIFICATION_ID = 1001
private const val CHANNEL_ID = "i996_tunnel"

class TunnelService : Service() {

    inner class LocalBinder : Binder() {
        fun getService(): TunnelService = this@TunnelService
    }

    private val binder = LocalBinder()
    private var client: TunnelClient? = null

    // ---- Lifecycle ---------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val token = intent.getStringExtra(EXTRA_TOKEN) ?: Prefs.getToken(this)
                val serverAddr = intent.getStringExtra(EXTRA_SERVER) ?: Prefs.getServer(this)
                if (token.isNotEmpty()) {
                    startForeground(NOTIFICATION_ID, buildNotification("连接中...", "正在连接到服务器"))
                    startTunnel(serverAddr, token)
                } else {
                    Log.e(TAG, "No token provided")
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                stopTunnel()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY   // Restart if killed by system
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Re-schedule ourselves so we survive swipe-away
        val restartIntent = Intent(applicationContext, TunnelService::class.java).apply {
            action = ACTION_START
            putExtra(EXTRA_TOKEN, client?.let { Prefs.getToken(this@TunnelService) } ?: "")
        }
        val pending = PendingIntent.getService(
            this, 1, restartIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmMgr = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmMgr.set(AlarmManager.ELAPSED_REALTIME, 2000, pending)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        stopTunnel()
        super.onDestroy()
    }

    // ---- Tunnel management -------------------------------------------------

    private fun startTunnel(serverAddr: String, token: String) {
        stopTunnel()

        client = TunnelClient(
            serverAddr = serverAddr,
            token = token,
            caPem = loadCert(),
            onLog = { msg ->
                LogBuffer.add(msg)
                broadcastLog(msg)
            },
            onAuthResult = { result ->
                broadcastAuthResult(result)
                if (result.ok) {
                    val webUrl = result.map["web_url"] ?: ""
                    updateNotification("已连接 ✓", webUrl.ifEmpty { "隧道运行中" })
                } else {
                    updateNotification("认证失败", result.msg)
                }
            },
            onConnected = {
                broadcastStatus(STATUS_CONNECTED)
            },
            onDisconnected = {
                broadcastStatus(STATUS_DISCONNECTED)
                updateNotification("已断开", "正在重连...")
            }
        )
        client!!.start()
    }

    private fun stopTunnel() {
        client?.stop()
        client = null
    }

    fun isConnected() = client?.isRunning() == true

    // ---- Notifications -----------------------------------------------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "i996 隧道服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持内网穿透隧道连接"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, TunnelService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_tunnel)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .addAction(R.drawable.ic_stop, "断开", stopPending)
            .build()
    }

    private fun updateNotification(title: String, text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(title, text))
    }

    // ---- Broadcasts --------------------------------------------------------

    private fun broadcastLog(msg: String) {
        val intent = Intent(BROADCAST_LOG).apply { putExtra(EXTRA_LOG_MSG, msg) }
        sendBroadcast(intent)
    }

    private fun broadcastStatus(status: String) {
        val intent = Intent(BROADCAST_STATUS).apply { putExtra(EXTRA_STATUS, status) }
        sendBroadcast(intent)
    }

    private fun broadcastAuthResult(result: AuthResult) {
        val intent = Intent(BROADCAST_AUTH_RESULT).apply {
            putExtra(EXTRA_AUTH_OK, result.ok)
            putExtra(EXTRA_AUTH_MSG, result.msg)
            result.map.forEach { (k, v) -> putExtra("map_$k", v) }
        }
        sendBroadcast(intent)
    }

    // ---- Cert --------------------------------------------------------------

    private fun loadCert(): String {
        return try {
            assets.open("ca.crt").bufferedReader().readText()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to load CA cert: ${e.message}")
            ""
        }
    }

    companion object {
        const val ACTION_START = "me.i996.client.START"
        const val ACTION_STOP = "me.i996.client.STOP"
        const val EXTRA_TOKEN = "token"
        const val EXTRA_SERVER = "server"

        const val BROADCAST_LOG = "me.i996.client.LOG"
        const val BROADCAST_STATUS = "me.i996.client.STATUS"
        const val BROADCAST_AUTH_RESULT = "me.i996.client.AUTH_RESULT"

        const val EXTRA_LOG_MSG = "log_msg"
        const val EXTRA_STATUS = "status"
        const val EXTRA_AUTH_OK = "auth_ok"
        const val EXTRA_AUTH_MSG = "auth_msg"

        const val STATUS_CONNECTED = "connected"
        const val STATUS_DISCONNECTED = "disconnected"

        fun startIntent(context: Context, token: String, server: String): Intent {
            return Intent(context, TunnelService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TOKEN, token)
                putExtra(EXTRA_SERVER, server)
            }
        }

        fun stopIntent(context: Context): Intent {
            return Intent(context, TunnelService::class.java).apply {
                action = ACTION_STOP
            }
        }
    }
}
