package com.sean.i996.tunnel

import android.util.Log
import java.io.*
import java.net.*
import java.security.cert.X509Certificate
import javax.net.ssl.*

class TunnelClient(
    private val serverAddr: String,
    private val clientId: String,
    private val certBytes: ByteArray? = null
) {
    companion object {
        private const val TAG = "TunnelClient"
        private const val CONTROL_ID = 0L
        private const val USER_CONN_ID_START = 128L

        // Commands
        private const val COMMAND_DATA = 0L
        private const val COMMAND_DATA_CONFIRM = 1L
        private const val COMMAND_DATA_WINDOW = 2L
        private const val COMMAND_CONNECT = 3L
        private const val COMMAND_CONNECT_CONFIRM = 4L
        private const val COMMAND_DIAL = 128L
        private const val COMMAND_ACCEPT = 129L
        private const val COMMAND_CLOSE = 130L
        private const val COMMAND_RESET = 131L
        private const val COMMAND_PING = 132L
        private const val COMMAND_PONG = 133L
        private const val COMMAND_TUNNEL_CLOSE = 134L
        private const val COMMAND_TUNNEL_CLOSE_CONFIRM = 135L
        private const val COMMAND_INFO = 136L
    }

    private var tlsSocket: SSLSocket? = null
    private var inputStream: BufferedInputStream? = null
    private var outputStream: BufferedOutputStream? = null
    private var isRunning = false

    fun getIsRunning(): Boolean = isRunning

    private val connections = mutableMapOf<Long, TunnelConnection>()
    private var lastConnectionId = USER_CONN_ID_START

    private val readerThread = Thread { readLoop() }
    private val writerThread = Thread { writeLoop() }
    private val writeQueue = mutableListOf<FrameData>()
    private val writeLock = Any()

    data class TunnelInfo(val id: String)

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

            // Create TLS socket with custom configuration
            val sslContext = createSSLContext()
            val socketFactory = sslContext.socketFactory

            // Connect directly using TLS socket factory
            tlsSocket = socketFactory.createSocket(serverAddr.split(":")[0], serverAddr.split(":")[1].toInt()) as SSLSocket

            // Configure SSL socket - match Go server configuration exactly
            tlsSocket?.apply {
                enabledProtocols = arrayOf("TLSv1.2") // Go server only supports TLS 1.2
                enabledCipherSuites = arrayOf(
                    "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
                    "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
                    "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA",
                    "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA"
                )
            }

            inputStream = BufferedInputStream(tlsSocket?.getInputStream())
            outputStream = BufferedOutputStream(tlsSocket?.getOutputStream())

            // Set socket timeout to match Go server (30 seconds)
            tlsSocket?.soTimeout = 30000

            // Send tunnel info immediately BEFORE starting threads
            // This matches Go client behavior: SetInfo immediately after connection
            val infoJson = """{"id":"${clientId}"}"""
            val infoBytes = infoJson.toByteArray()

            // Write INFO frame directly (synchronous)
            writeUInt64(CONTROL_ID)
            writeUInt64(COMMAND_INFO)
            writeUInt64(infoBytes.size.toLong())
            outputStream?.write(infoBytes)
            outputStream?.flush()

            isRunning = true
            readerThread.start()
            writerThread.start()

            Log.d(TAG, "Connected to server successfully")
            listener?.onConnected()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to server", e)
            listener?.onError("Connection failed: ${e.message}")
            false
        }
    }

    private fun createSSLContext(): SSLContext {
        val sslContext = SSLContext.getInstance("TLS")

        // Create trust manager that accepts all certificates (for development)
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })

        sslContext.init(null, trustAllCerts, java.security.SecureRandom())
        return sslContext
    }

    private fun sendTunnelInfo(info: TunnelInfo) {
        try {
            val infoJson = """{"id":"${info.id}"}"""
            val infoBytes = infoJson.toByteArray()

            synchronized(writeLock) {
                writeQueue.add(FrameInfo(data = infoBytes))
                (writeLock as Object).notify()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send tunnel info", e)
        }
    }

    
    private fun readLoop() {
        try {
            while (isRunning) {
                val connectionId = readUInt64()
                Log.d(TAG, "Read connection ID: $connectionId")

                when {
                    connectionId == CONTROL_ID -> {
                        processControlCommand()
                    }
                    connectionId >= USER_CONN_ID_START -> {
                        processDataMessage(connectionId)
                    }
                    else -> {
                        Log.e(TAG, "Unexpected connection ID: $connectionId")
                        disconnect()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Read loop error", e)
            if (isRunning) {
                listener?.onError("Read error: ${e.message}")
            }
        }
    }

    private fun writeLoop() {
        try {
            while (isRunning) {
                val frame: FrameData? = synchronized(writeLock) {
                    if (writeQueue.isEmpty()) {
                        (writeLock as Object).wait(5000) // Wait for data or timeout
                        // Send ping every 5 seconds
                        if (writeQueue.isEmpty()) {
                            writeQueue.add(FramePing())
                        }
                    }
                    if (writeQueue.isNotEmpty()) writeQueue.removeAt(0) else null
                }

                frame?.let { writeFrame(it) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Write loop error", e)
        }
    }

    private fun processControlCommand() {
        try {
            val command = readUInt64()
            Log.d(TAG, "Processing control command: $command")

            when (command) {
                COMMAND_DIAL -> {
                    val connectionId = readUInt64()
                    Log.d(TAG, "Received dial command for connection: $connectionId")
                    listener?.onConnectionRequest(connectionId)
                }
                COMMAND_ACCEPT -> {
                    val connectionId = readUInt64()
                    Log.d(TAG, "Received accept command for connection: $connectionId")
                    connections[connectionId]?.let { conn ->
                        conn.onAccepted()
                    }
                }
                COMMAND_CLOSE -> {
                    val connectionId = readUInt64()
                    Log.d(TAG, "Received close command for connection: $connectionId")
                    closeConnection(connectionId)
                }
                COMMAND_RESET -> {
                    val connectionId = readUInt64()
                    Log.d(TAG, "Received reset command for connection: $connectionId")
                    resetConnection(connectionId)
                }
                COMMAND_DATA_CONFIRM -> {
                    val connectionId = readUInt64()
                    val windowSize = readUInt64().toInt()
                    Log.d(TAG, "Received data confirm for connection: $connectionId, window: $windowSize")
                    connections[connectionId]?.onDataConfirmed(windowSize)
                }
                COMMAND_PING -> {
                    Log.d(TAG, "Received ping, sending pong")
                    synchronized(writeLock) {
                        writeQueue.add(FramePong())
                        (writeLock as Object).notify()
                    }
                }
                COMMAND_TUNNEL_CLOSE -> {
                    Log.d(TAG, "Received tunnel close")
                    synchronized(writeLock) {
                        writeQueue.add(FrameTunnelCloseConfirm())
                        (writeLock as Object).notify()
                    }
                    disconnect()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing control command", e)
        }
    }

    private fun processDataMessage(connectionId: Long) {
        try {
            val dataSize = readUInt64()
            Log.d(TAG, "Processing data message for connection: $connectionId, size: $dataSize")

            val data = ByteArray(dataSize.toInt())
            var bytesRead = 0

            while (bytesRead < dataSize.toInt()) {
                val read = inputStream?.read(data, bytesRead, dataSize.toInt() - bytesRead) ?: 0
                if (read == -1) {
                    Log.e(TAG, "Unexpected end of stream while reading data")
                    disconnect()
                    return
                }
                bytesRead += read
            }

            connections[connectionId]?.onDataReceived(data)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing data message", e)
        }
    }

    private fun readUInt64(): Long {
        var result = 0L
        var shift = 0
        var b: Int

        do {
            b = inputStream?.read() ?: -1
            if (b == -1) throw EOFException("Unexpected end of stream")

            result = result or (((b and 0x7F).toLong() shl shift))
            shift += 7
        } while (b and 0x80 != 0)

        return result
    }

    private fun writeFrame(frame: FrameData) {
        try {
            when (frame) {
                is FramePing -> {
                    writeUInt64(CONTROL_ID)
                    writeUInt64(COMMAND_PING)
                }
                is FramePong -> {
                    writeUInt64(CONTROL_ID)
                    writeUInt64(COMMAND_PONG)
                }
                is FrameTunnelCloseConfirm -> {
                    writeUInt64(CONTROL_ID)
                    writeUInt64(COMMAND_TUNNEL_CLOSE_CONFIRM)
                }
                is FrameInfo -> {
                    writeUInt64(CONTROL_ID)
                    writeUInt64(COMMAND_INFO)
                    writeUInt64(frame.data.size.toLong())
                    outputStream?.write(frame.data)
                }
                is TunnelFrameData -> {
                    writeUInt64(frame.connectionId)
                    writeUInt64(frame.data.size.toLong())
                    outputStream?.write(frame.data)
                }
                is FrameAccept -> {
                    writeUInt64(CONTROL_ID)
                    writeUInt64(COMMAND_ACCEPT)
                    writeUInt64(frame.connectionId)
                }
                is FrameClose -> {
                    writeUInt64(CONTROL_ID)
                    writeUInt64(COMMAND_CLOSE)
                    writeUInt64(frame.connectionId)
                }
                is FrameReset -> {
                    writeUInt64(CONTROL_ID)
                    writeUInt64(COMMAND_RESET)
                    writeUInt64(frame.connectionId)
                }
                is FrameDataConfirm -> {
                    writeUInt64(CONTROL_ID)
                    writeUInt64(COMMAND_DATA_CONFIRM)
                    writeUInt64(frame.connectionId)
                    writeUInt64(frame.windowSize.toLong())
                }
            }
            outputStream?.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Error writing frame", e)
        }
    }

    private fun writeUInt64(value: Long) {
        var v = value
        do {
            var b = (v and 0x7F).toByte()
            v = v ushr 7
            if (v != 0L) {
                b = (b.toInt() or 0x80).toByte()
            }
            outputStream?.write(b.toInt())
        } while (v != 0L)
    }

    fun createConnection(): TunnelConnection {
        val connectionId = lastConnectionId++
        val connection = TunnelConnection(connectionId, this)
        connections[connectionId] = connection
        return connection
    }

    internal fun sendData(connectionId: Long, data: ByteArray): Boolean {
        return try {
            synchronized(writeLock) {
                writeQueue.add(TunnelFrameData(connectionId, data))
                (writeLock as Object).notify()
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send data", e)
            false
        }
    }

    internal fun acceptConnection(connectionId: Long): Boolean {
        return try {
            synchronized(writeLock) {
                writeQueue.add(FrameAccept(connectionId))
                (writeLock as Object).notify()
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to accept connection", e)
            false
        }
    }

    internal fun closeConnection(connectionId: Long): Boolean {
        return try {
            connections.remove(connectionId)
            synchronized(writeLock) {
                writeQueue.add(FrameClose(connectionId))
                (writeLock as Object).notify()
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to close connection", e)
            false
        }
    }

    internal fun resetConnection(connectionId: Long): Boolean {
        return try {
            connections.remove(connectionId)
            synchronized(writeLock) {
                writeQueue.add(FrameReset(connectionId))
                (writeLock as Object).notify()
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reset connection", e)
            false
        }
    }

    internal fun sendDataConfirm(connectionId: Long, windowSize: Int): Boolean {
        return try {
            synchronized(writeLock) {
                writeQueue.add(FrameDataConfirm(connectionId, windowSize))
                (writeLock as Object).notify()
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send data confirm", e)
            false
        }
    }

    fun disconnect() {
        isRunning = false
        try {
            synchronized(writeLock) {
                (writeLock as Object).notifyAll()
            }

            inputStream?.close()
            outputStream?.close()
            tlsSocket?.close()

            connections.clear()

            listener?.onDisconnected()
            Log.d(TAG, "Disconnected from server")
        } catch (e: Exception) {
            Log.e(TAG, "Error during disconnect", e)
        }
    }
}