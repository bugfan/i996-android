package com.sean.i996

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.net.Socket
import java.security.cert.X509Certificate
import javax.net.ssl.*

class TunnelService : Service() {
    private val binder = TunnelBinder()
    private var client: TunnelClient? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    inner class TunnelBinder : Binder() {
        fun getService(): TunnelService = this@TunnelService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        startForeground(1, createNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val serverAddr = intent?.getStringExtra("serverAddr") ?: "192.168.1.130:3333"
        val clientId = "testid"
        val certPem = "-----BEGIN CERTIFICATE-----\n" +
"MIIDeDCCAmACCQCoWat+3pYh5zANBgkqhkiG9w0BAQUFADB+MQswCQYDVQQGEwJD\n" +
"TjEQMA4GA1UECAwHQmVpamluZzERMA8GA1UEBwwISW50ZXJuZXQxDzANBgNVBAoM\n" +
"BlNhZmV0eTEMMAoGA1UECwwDVlBOMQowCAYDVQQDDAFSMR8wHQYJKoZIhvcNAQkB\n" +
"FhA5MDg5NTgxOTRAcXEuY29tMB4XDTI0MDExOTA3MDI0OFoXDTI0MDIxODA3MDI0\n" +
"OFowfjELMAkGA1UEBhMCQ04xEDAOBgNVBAgMB0JlaWppbmcxETAPBgNVBAcMCElu\n" +
"dGVybmV0MQ8wDQYDVQQKDAZTYWZldHkxDDAKBgNVBAsMA1ZQTjEKMAgGA1UEAwwB\n" +
"UjEfMB0GCSqGSIb3DQEJARYQOTA4OTU4MTk0QHFxLmNvbTCCASIwDQYJKoZIhvcN\n" +
"AQEBBQADggEPADCCAQoCggEBAM8F7Nj9Cv4V0f/5JhX7H+nzXmdwIpMnyf2REhXd\n" +
"qq/m/Z4iQt6guxcYB3Z9XmI+t3PlHZo6hWxJkxWTnEe/MpB5Qmy5EpjG4kVo2RqF\n" +
"ndCFeZ2GuiWS0T307CNslpskSOAAxSo4PIIHEZuC/Muu7aNB88VuTlEn+NsLEhy5\n" +
"56T00Eq3Akrbm7yeZsZ5+4uJJtpd2LeFQDG8A3F9aqBSl9d+yAc0oh6GyRcuzgcJ\n" +
"1dk5HwxuszRghBFHzFKH+pNY7mA2HabSCudwVV+iCOK5+vnfUIXGJpOuNJJ8mVuu\n" +
"hh+LAawVs0mnUi4A8DVeEtGxA1R8OtTUSI8QW4e6893w55sCAwEAATANBgkqhkiG\n" +
"9w0BAQUFAAOCAQEAWALtpIkdiKuci6VUC7CqgWcQQnZAQZ5KoNwpueF6rE2EvJ7b\n" +
"kINYwWV5q/YldlqRjaJaWdZi5QcyJqb3UwaKVy4WxIDRY2L7/zP/ppEIOb2HImdk\n" +
"tWOz9dRTejAOUlG70+6JVcHps+N2Z5Wr2FILPWGWVNcZZPeAhSRdDpttRPEbx8hV\n" +
"3bBUMVtQs7FimeGo2l77UJPQsFVF8wYj2Rrn9yx5YJhdfu95j1eM+VzlLI1eDxuC\n" +
"nv1ejJMoUPz7cQsfvZGWkmR6PrO1GDuk0oQL/zU5Af21utbOIQXMve4RVgbx+aVp\n" +
"lPm6Itkzgyl+Set4WHZQe8ZXbexzdmtLF+TRyg==\n" +
"-----END CERTIFICATE-----\n"
        
        startTunnel(serverAddr, clientId, certPem)
        return START_STICKY
    }

    private fun startTunnel(serverAddr: String, clientId: String, certPem: String?) {
        serviceScope.launch {
            while (isActive) {
                try {
                    client = TunnelClient(serverAddr, clientId, certPem)
                    client?.run()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                delay(10000)
            }
        }
    }

    private fun createNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "tunnel_service",
                "Tunnel Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, "tunnel_service")
            .setContentTitle("Tunnel Running")
            .setContentText("Connected to server")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        client?.close()
        super.onDestroy()
    }
}

class TunnelClient(
    private val serverAddr: String,
    private val clientId: String,
    private val certPem: String?
) {
    private var frameConn: FrameConn? = null

    suspend fun run() {
        val sslContext = createSSLContext(certPem)
        val factory = sslContext.socketFactory

        val parts = serverAddr.split(":")
        val host = parts[0]
        val port = parts[1].toInt()

        val socket = withContext(Dispatchers.IO) {
            factory.createSocket(host, port) as SSLSocket
        }

        println("Connected with $serverAddr")

        frameConn = FrameConn(socket, true)

        // 延迟一点时间确保连接建立完成再发送Info
        delay(100)
        frameConn?.setInfo(Info(clientId))
        println("Info sent with client ID: $clientId")

        while (true) {
            try {
                println("Accepting connection...")
                val conn = frameConn?.accept() ?: break

                GlobalScope.launch(Dispatchers.IO) {
                    try {
                        conn.proxy()
                    } catch (e: Exception) {
                        println("Proxy error: ${e.message}")
                        e.printStackTrace()
                    }
                }
            } catch (e: Exception) {
                println("Tunnel error: ${e.message}")
                e.printStackTrace()
                break
            }
        }
    }

    fun close() {
        frameConn?.close()
    }

    private fun createSSLContext(certPem: String?): SSLContext {
        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf(trustManager), java.security.SecureRandom())
        return sslContext
    }
}