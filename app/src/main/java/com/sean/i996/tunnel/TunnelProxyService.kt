package com.sean.i996.tunnel

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.*

class TunnelProxyService : Service() {
    companion object {
        private const val TAG = "TunnelProxyService"

        // Default targets for common services
        private val DEFAULT_TARGETS = mapOf(
            "http" to Pair("www.google.com", 80),
            "https" to Pair("www.google.com", 443),
            "socks" to Pair("socks5.example.com", 1080)
        )
    }

    private val binder = TunnelBinder()
    private var tunnelClient: TunnelClient? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // LiveData for observing connection status
    val connectionStatus = MutableLiveData<ConnectionStatus>()
    val activeConnections = MutableLiveData<Int>()
    val totalDataTransferred = MutableLiveData<Long>()

    private val activeConnectionMap = mutableMapOf<Long, TunnelConnection>()
    private var totalBytesTransferred = 0L

    enum class ConnectionStatus {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        ERROR
    }

    inner class TunnelBinder : Binder() {
        fun getService(): TunnelProxyService = this@TunnelProxyService
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Tunnel proxy service created")
        connectionStatus.postValue(ConnectionStatus.DISCONNECTED)
        activeConnections.postValue(0)
        totalDataTransferred.postValue(0L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "START_TUNNEL" -> {
                val serverAddr = intent.getStringExtra("SERVER_ADDRESS") ?: return START_NOT_STICKY
                val clientId = intent.getStringExtra("CLIENT_ID") ?: "android-${System.currentTimeMillis()}"
                startTunnel(serverAddr, clientId)
            }
            "STOP_TUNNEL" -> {
                stopTunnel()
            }
        }
        return START_STICKY
    }

    private fun startTunnel(serverAddr: String, clientId: String) {
        if (tunnelClient?.let { it.getIsRunning() } == true) {
            Log.d(TAG, "Tunnel already running")
            return
        }

        connectionStatus.postValue(ConnectionStatus.CONNECTING)

        serviceScope.launch {
            try {
                tunnelClient = TunnelClient(serverAddr, clientId).apply {
                    setListener(object : TunnelClient.TunnelConnectionListener {
                        override fun onConnected() {
                            Log.d(TAG, "Tunnel connected successfully")
                            connectionStatus.postValue(ConnectionStatus.CONNECTED)
                            startForegroundService()
                        }

                        override fun onDisconnected() {
                            Log.d(TAG, "Tunnel disconnected")
                            connectionStatus.postValue(ConnectionStatus.DISCONNECTED)
                            activeConnectionMap.clear()
                            activeConnections.postValue(0)
                            stopForeground(true)
                        }

                        override fun onConnectionRequest(connectionId: Long) {
                            Log.d(TAG, "Received connection request: $connectionId")
                            handleConnectionRequest(connectionId)
                        }

                        override fun onError(error: String) {
                            Log.e(TAG, "Tunnel error: $error")
                            connectionStatus.postValue(ConnectionStatus.ERROR)
                            stopForeground(true)
                        }
                    })
                }

                // Connect in background
                withContext(Dispatchers.IO) {
                    tunnelClient?.connect()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start tunnel", e)
                connectionStatus.postValue(ConnectionStatus.ERROR)
            }
        }
    }

    private fun handleConnectionRequest(connectionId: Long) {
        serviceScope.launch {
            try {
                val connection = tunnelClient?.createConnection() ?: return@launch

                connection.setListener(object : TunnelConnection.TunnelConnectionListener {
                    override fun onConnected() {
                        Log.d(TAG, "Target connection established for: $connectionId")
                        activeConnectionMap[connectionId] = connection
                        activeConnections.postValue(activeConnectionMap.size)
                    }

                    override fun onDisconnected() {
                        Log.d(TAG, "Target connection closed for: $connectionId")
                        activeConnectionMap.remove(connectionId)
                        activeConnections.postValue(activeConnectionMap.size)
                    }

                    override fun onError(error: String) {
                        Log.e(TAG, "Target connection error for $connectionId: $error")
                        activeConnectionMap.remove(connectionId)
                        activeConnections.postValue(activeConnectionMap.size)
                    }

                    override fun onDataReceived(data: ByteArray) {
                        totalBytesTransferred += data.size
                        totalDataTransferred.postValue(totalBytesTransferred)
                    }
                })

                // Accept the tunnel connection
                if (tunnelClient?.acceptConnection(connectionId) == true) {
                    // For this example, we'll connect to a default target
                    // In a real implementation, you might want to determine the target
                    // based on the first data packet or some other logic
                    val defaultTarget = DEFAULT_TARGETS["https"]!!
                    connection.connect(defaultTarget.first, defaultTarget.second)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling connection request", e)
            }
        }
    }

    private fun startForegroundService() {
        // Create notification for foreground service
        val channelId = "tunnel_service_channel"

        // Implementation would depend on your notification preferences
        // For now, just log that service is running as foreground
        Log.d(TAG, "Starting tunnel service as foreground")
    }

    private fun stopTunnel() {
        Log.d(TAG, "Stopping tunnel")

        serviceScope.launch {
            try {
                // Close all active connections
                activeConnectionMap.values.forEach { connection ->
                    connection.close()
                }
                activeConnectionMap.clear()
                activeConnections.postValue(0)

                // Disconnect tunnel client
                tunnelClient?.disconnect()
                tunnelClient = null

                connectionStatus.postValue(ConnectionStatus.DISCONNECTED)
                stopForeground(true)
                stopSelf()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping tunnel", e)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Tunnel proxy service destroyed")

        serviceScope.cancel()
        stopTunnel()
    }

    // Public API for Activity/Fragment interaction
    fun getConnectionStatus(): ConnectionStatus? {
        return connectionStatus.value
    }

    fun getActiveConnectionsCount(): Int {
        return activeConnections.value ?: 0
    }

    fun getTotalDataTransferred(): Long {
        return totalDataTransferred.value ?: 0L
    }

    fun isRunning(): Boolean {
        return tunnelClient?.let { it.getIsRunning() } ?: false
    }
}