package com.sean.i996.tunnel

import android.util.Log
import java.io.*
import java.net.*
import java.security.cert.X509Certificate
import javax.net.ssl.*

class TunnelClientSimple(
    private val serverAddr: String,
    private val clientId: String
) {
    companion object {
        private const val TAG = "TunnelClient"
    }

    private var socket: Socket? = null
    private var isRunning = false

    interface TunnelConnectionListener {
        fun onConnected()
        fun onDisconnected()
        fun onConnectionRequest(connectionId: Long)
        fun onError(error: String)
    }

    private var listener: TunnelConnectionListener? = null

    fun setListener(listener: TunnelConnectionListener) {
        this.listener = listener
    }

    fun connect(): Boolean {
        return try {
            Log.d(TAG, "Connecting to server: $serverAddr")

            // 简单的TLS连接，不搞复杂配置
            val sslContext = createSSLContext()
            val socketFactory = sslContext.socketFactory

            val parts = serverAddr.split(":")
            socket = socketFactory.createSocket(parts[0], parts[1].toInt()) as SSLSocket

            // 发送JSON info，就像Go客户端一样
            val infoJson = """{"id":"${clientId}"}"""
            val output = socket?.getOutputStream()
            output?.write(infoJson.toByteArray())
            output?.flush()

            Log.d(TAG, "Connected and sent info: $infoJson")

            isRunning = true

            // 启动读取循环等待连接请求
            Thread {
                runLoop()
            }.start()

            listener?.onConnected()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect: ${e.message}", e)
            listener?.onError("Connection failed: ${e.message}")
            false
        }
    }

    private fun runLoop() {
        try {
            while (isRunning) {
                // 简单的读取循环，等待服务器发送连接请求
                val input = socket?.getInputStream()
                val buffer = ByteArray(1024)
                val bytesRead = input?.read(buffer)

                if (bytesRead != null && bytesRead > 0) {
                    val message = String(buffer, 0, bytesRead)
                    Log.d(TAG, "Received: $message")

                    // 假设服务器发送连接请求
                    if (message.contains("DIAL")) {
                        listener?.onConnectionRequest(System.currentTimeMillis())
                    }
                } else if (bytesRead == -1) {
                    Log.d(TAG, "Server closed connection")
                    break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Run loop error: ${e.message}", e)
            if (isRunning) {
                listener?.onError("Run loop error: ${e.message}")
            }
        } finally {
            disconnect()
        }
    }

    private fun createSSLContext(): SSLContext {
        val sslContext = SSLContext.getInstance("TLS")

        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })

        sslContext.init(null, trustAllCerts, java.security.SecureRandom())
        return sslContext
    }

    fun disconnect() {
        isRunning = false
        try {
            socket?.close()
            Log.d(TAG, "Disconnected")
            listener?.onDisconnected()
        } catch (e: Exception) {
            Log.e(TAG, "Error during disconnect", e)
        }
    }
}