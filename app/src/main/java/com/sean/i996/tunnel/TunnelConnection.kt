package com.sean.i996.tunnel

import android.util.Log
import java.io.IOException
import java.net.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

class TunnelConnection(
    private val connectionId: Long,
    private val tunnelClient: TunnelClient
) {
    companion object {
        private const val TAG = "TunnelConnection"
        private const val BUFFER_SIZE = 8192
        private const val READ_TIMEOUT_MS = 30000
    }

    private val isConnected = AtomicBoolean(false)
    private val isClosed = AtomicBoolean(false)
    private val accepted = AtomicBoolean(false)

    private var targetSocket: Socket? = null
    private var targetInputStream: java.io.InputStream? = null
    private var targetOutputStream: java.io.OutputStream? = null

    private val readExecutor = Executors.newSingleThreadExecutor()
    private val writeExecutor = Executors.newSingleThreadExecutor()

    private val dataQueue = LinkedBlockingQueue<ByteArray>()
    private var canWrite = AtomicBoolean(false)

    private var connectFuture: CompletableFuture<Boolean>? = null
    private var readJob: Future<*>? = null
    private var writeJob: Future<*>? = null

    interface TunnelConnectionListener {
        fun onConnected()
        fun onDisconnected()
        fun onError(error: String)
        fun onDataReceived(data: ByteArray)
    }

    private var connectionListener: TunnelConnectionListener? = null

    fun setListener(listener: TunnelConnectionListener) {
        this.connectionListener = listener
    }

    fun connect(targetHost: String, targetPort: Int): CompletableFuture<Boolean> {
        if (isConnected.get()) {
            return CompletableFuture.completedFuture(true)
        }

        connectFuture = CompletableFuture<Boolean>()

        Log.d(TAG, "Connecting to target: $targetHost:$targetPort")

        writeExecutor.submit {
            try {
                // Connect to target server
                targetSocket = Socket()
                targetSocket?.apply {
                    connect(InetSocketAddress(targetHost, targetPort), 10000) // 10 second timeout
                    soTimeout = READ_TIMEOUT_MS
                }

                targetInputStream = targetSocket?.getInputStream()
                targetOutputStream = targetSocket?.getOutputStream()

                Log.d(TAG, "Connected to target: $targetHost:$targetPort")

                isConnected.set(true)
                connectionListener?.onConnected()

                // Start reading from target
                startReadingFromTarget()

                connectFuture?.complete(true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect to target: $targetHost:$targetPort", e)
                connectionListener?.onError("Failed to connect: ${e.message}")
                close()
                connectFuture?.complete(false)
            }
        }

        return connectFuture!!
    }

    fun onAccepted() {
        Log.d(TAG, "Connection $connectionId accepted by server")
        accepted.set(true)
        canWrite.set(true)

        // Start processing queued data
        startProcessingData()
    }

    fun onDataReceived(data: ByteArray) {
        if (isClosed.get()) return

        try {
            targetOutputStream?.write(data)
            targetOutputStream?.flush()

            // Send data confirmation to tunnel server
            tunnelClient.sendDataConfirm(connectionId, dataQueue.remainingCapacity())
        } catch (e: Exception) {
            Log.e(TAG, "Error writing data to target", e)
            connectionListener?.onError("Write error: ${e.message}")
            close()
        }
    }

    fun onDataConfirmed(windowSize: Int) {
        Log.d(TAG, "Data confirmed for connection $connectionId, window size: $windowSize")
        canWrite.set(windowSize > 0)
    }

    private fun startReadingFromTarget() {
        readJob = readExecutor.submit {
            try {
                val buffer = ByteArray(BUFFER_SIZE)

                while (!isClosed.get() && !Thread.currentThread().isInterrupted) {
                    val bytesRead = targetInputStream?.read(buffer) ?: -1

                    if (bytesRead == -1) {
                        Log.d(TAG, "Target connection closed")
                        break
                    }

                    if (bytesRead > 0) {
                        val data = buffer.copyOf(bytesRead)

                        // Send data through tunnel
                        if (!tunnelClient.sendData(connectionId, data)) {
                            Log.e(TAG, "Failed to send data through tunnel")
                            break
                        }

                        connectionListener?.onDataReceived(data)
                    }
                }
            } catch (e: Exception) {
                if (!isClosed.get()) {
                    Log.e(TAG, "Error reading from target", e)
                    connectionListener?.onError("Read error: ${e.message}")
                }
            } finally {
                close()
            }
        }
    }

    private fun startProcessingData() {
        writeJob = writeExecutor.submit {
            try {
                while (!isClosed.get() && !Thread.currentThread().isInterrupted) {
                    val data = dataQueue.poll(1, TimeUnit.SECONDS) ?: continue

                    if (!canWrite.get()) {
                        // Re-queue data if we can't write now
                        dataQueue.offer(data)
                        Thread.sleep(100)
                        continue
                    }

                    onDataReceived(data)
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                Log.e(TAG, "Error processing data", e)
                connectionListener?.onError("Process error: ${e.message}")
            }
        }
    }

    fun close() {
        if (isClosed.compareAndSet(false, true)) {
            Log.d(TAG, "Closing connection $connectionId")

            isConnected.set(false)
            canWrite.set(false)

            // Cancel running jobs
            readJob?.cancel(true)
            writeJob?.cancel(true)

            // Close target connection
            try {
                targetInputStream?.close()
                targetOutputStream?.close()
                targetSocket?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing target socket", e)
            }

            // Close tunnel connection
            tunnelClient.closeConnection(connectionId)

            // Shutdown executors
            readExecutor.shutdown()
            writeExecutor.shutdown()

            connectionListener?.onDisconnected()
        }
    }

    fun reset() {
        if (isClosed.get()) return

        Log.d(TAG, "Resetting connection $connectionId")
        tunnelClient.resetConnection(connectionId)
        close()
    }

    fun isActive(): Boolean = isConnected.get() && !isClosed.get()

    fun getConnectionId(): Long = connectionId

    fun getTargetAddress(): String? {
        return targetSocket?.inetAddress?.hostAddress
    }
}