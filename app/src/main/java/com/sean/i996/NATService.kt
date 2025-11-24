package com.i996.nat

import android.app.*
import android.content.Intent
import android.os.*
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.IOException

class NATService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var sshManager: SSHTunnelManager? = null
    private var reconnectJob: Job? = null

    companion object {
        const val CHANNEL_ID = "nat_service_channel"
        const val NOTIFICATION_ID = 1001
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        updateServiceStatus(true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val token = intent?.getStringExtra("token") ?: ""
        val privateHost = intent?.getStringExtra("private_host") ?: "127.0.0.1:8080"

        startForeground(NOTIFICATION_ID, createNotification("正在启动..."))

        serviceScope.launch {
            startTunnel(token, privateHost)
        }

        return START_STICKY
    }

    private suspend fun startTunnel(token: String, privateHost: String) {
        try {
            sendLog("验证Token中...")
            updateNotification("验证Token中...")

            // Token验证
            val authResult = ApiClient.authenticate(token)
            if (authResult == null) {
                sendLog("Token验证失败！")
                updateNotification("Token验证失败")
                delay(3000)
                stopSelf()
                return
            }

            sendLog("Token验证通过!")
            val (publicHost, configPrivateHost) = authResult
            val actualPrivateHost = if (configPrivateHost.isNotEmpty()) configPrivateHost else privateHost

            sendLog("公网地址: $publicHost")
            sendLog("内网地址: $actualPrivateHost")

            // 解析内网地址
            val parts = actualPrivateHost.split(":")
            val privateAddr = parts[0]
            val privatePort = parts.getOrNull(1)?.toIntOrNull() ?: 8080

            // 建立SSH隧道
            sshManager = SSHTunnelManager(token, privateAddr, privatePort)
            updateNotification("连接中...")

            sshManager?.connect { output ->
                serviceScope.launch(Dispatchers.Main) {
                    handleSSHOutput(output, publicHost, actualPrivateHost)
                }
            }

        } catch (e: Exception) {
            sendLog("错误: ${e.message}")
            updateNotification("连接失败")
            scheduleReconnect(token, privateHost)
        }
    }

    private fun handleSSHOutput(output: String, publicHost: String, privateHost: String) {
        when {
            output.contains("ClothoAllocatedPort") -> {
                val port = output.substringAfter("ClothoAllocatedPort")
                sendLog("i996内网穿透启动成功！！！")
                sendLog("公网地址 =======> https://$publicHost")
                sendLog("..                http://$publicHost")
                sendLog("..                tcp://$publicHost:$port")
                sendLog("内网地址 =======> $privateHost")
                sendLog("【温馨提示】您正在使用i996新版本！")
                updateNotification("运行中 - $publicHost")
            }
            output.contains("ClothoUpdatePrivate") -> {
                sendLog("配置已更新，正在重启连接...")
                sshManager?.disconnect()
            }
            output.isNotEmpty() -> {
                sendLog(output)
            }
        }
    }

    private fun scheduleReconnect(token: String, privateHost: String) {
        reconnectJob?.cancel()
        reconnectJob = serviceScope.launch {
            sendLog("正在尝试重连,请稍等～")
            delay(5000)
            startTunnel(token, privateHost)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "内网穿透服务",
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
            .setContentTitle("i996内网穿透")
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
        sshManager?.disconnect()
        reconnectJob?.cancel()
        serviceScope.cancel()
        updateServiceStatus(false)
        sendLog("服务已停止")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
