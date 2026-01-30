package com.i996.nat

import android.app.*
import android.content.Intent
import android.os.*
import androidx.core.app.NotificationCompat
import com.sean.i996.libi996.I996Client
import com.sean.i996.libi996.Logger
import kotlinx.coroutines.*
import java.io.File

class NATService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var i996Client: I996Client? = null

    companion object {
        const val CHANNEL_ID = "nat_service_channel"
        const val NOTIFICATION_ID = 1001
        const val SERVER_ADDR = "i996.me:8223"
        const val FIXED_TOKEN = "tian"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        updateServiceStatus(true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification("正在启动..."))

        serviceScope.launch {
            startTunnel()
        }

        return START_STICKY
    }

    private suspend fun startTunnel() {
        try {
            sendLog("正在初始化 i996 客户端...")
            updateNotification("正在连接...")

            // 读取证书
            val certFile = File(filesDir, "cert.pem")
            val certPem = if (certFile.exists()) {
                certFile.readBytes()
            } else {
                assets.open("cert.pem").use { it.readBytes() }
            }

            // 创建 Go 客户端
            i996Client = I996Client()

            // 设置日志回调
            i996Client?.setLogger(object : Logger {
                override fun log(message: String) {
                    sendLog(message)
                }
            })

            // 设置配置
            i996Client?.setConfig(SERVER_ADDR, FIXED_TOKEN, certPem)

            sendLog("Token: $FIXED_TOKEN")
            sendLog("服务器: $SERVER_ADDR")

            // 启动客户端
            i996Client?.start()

            sendLog("i996 内网穿透已启动！")
            updateNotification("运行中")

        } catch (e: Exception) {
            sendLog("错误: ${e.message}")
            e.printStackTrace()
            updateNotification("启动失败")
            delay(3000)
            stopSelf()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "i996 内网穿透",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(content: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("i996 内网穿透")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(content: String) {
        val notification = createNotification(content)
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, notification)
    }

    private fun sendLog(message: String) {
        android.util.Log.d("NATService", "发送日志: $message")

        // 保存到 SharedPreferences 供 Activity 读取
        val prefs = getSharedPreferences("i996_logs", MODE_PRIVATE)
        val logs = prefs.getStringSet("logs", null)?.toMutableList() ?: mutableListOf()

        // 添加新日志（最多保留 500 条）
        logs.add(0, "${System.currentTimeMillis()}:$message")
        if (logs.size > 500) {
            logs.removeAt(logs.size - 1)
        }

        prefs.edit().putStringSet("logs", logs.toSet()).apply()

        // 同时也发送广播（用于实时更新）
        val intent = Intent("com.i996.nat.LOG")
        intent.putExtra("log", message)
        sendBroadcast(intent)
    }

    private fun updateServiceStatus(running: Boolean) {
        val prefs = getSharedPreferences("nat_config", MODE_PRIVATE)
        prefs.edit().putBoolean("service_running", running).apply()
    }

    override fun onDestroy() {
        super.onDestroy()
        i996Client?.stop()
        serviceScope.cancel()
        updateServiceStatus(false)
        sendLog("服务已停止")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
