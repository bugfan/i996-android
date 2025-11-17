package com.sean.i996

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.*
import java.net.Socket

class Conn(
    val id: Long,
    private val frame: FrameConn
) {
    private val readChannel = Channel<ByteArray>(128)
    private val buffer = ByteArrayOutputStream()
    private val dialChannel = Channel<Unit>(1)
    private val connectChannel = Channel<Unit>(1)
    private var connectAddr: String? = null
    private var writeAble = Channel<Unit>(1)
    private val writeDone = Channel<Unit>(1)
    private var closed = false
    private var error: Exception? = null

    init {
        writeAble.trySend(Unit)
    }

    suspend fun read(b: ByteArray): Int {
        if (buffer.size() == 0) {
            val data = readChannel.receive()
            buffer.write(data)
        }

        val available = buffer.size()
        val toRead = minOf(available, b.size)
        val bytes = buffer.toByteArray()
        System.arraycopy(bytes, 0, b, 0, toRead)

        buffer.reset()
        if (toRead < available) {
            buffer.write(bytes, toRead, available - toRead)
        }

        return toRead
    }

    suspend fun write(b: ByteArray): Int {
        if (closed) throw IOException("Connection closed")

        // 等待可写状态
        writeAble.receive()

        frame.writeData(id, b)

        // 等待写完成确认
        writeDone.receive()

        return b.size
    }

    suspend fun proxy() {
        connectChannel.receive()
        val addr = connectAddr ?: throw Exception("No connect address")

        try {
            val parts = addr.split(":")
            val targetSocket = withContext(Dispatchers.IO) {
                Socket(parts[0], parts[1].toInt())
            }

            println("Proxy connected to $addr")
            frame.writeConnectConfirm(id, null)

            // 从目标服务器读取数据并写入隧道
            val job1 = GlobalScope.launch(Dispatchers.IO) {
                try {
                    val input = targetSocket.getInputStream()
                    val buf = ByteArray(32 * 1024)

                    while (!closed) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        write(buf.copyOf(n))
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    targetSocket.close()
                    close()
                }
            }

            // 从隧道读取数据并写入目标服务器
            val job2 = GlobalScope.launch(Dispatchers.IO) {
                try {
                    val output = targetSocket.getOutputStream()
                    val buf = ByteArray(32 * 1024)

                    while (!closed) {
                        val n = read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        output.flush()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    targetSocket.close()
                    close()
                }
            }

            job1.join()
            job2.join()

        } catch (e: Exception) {
            println("Proxy connect failed: ${e.message}")
            frame.writeConnectConfirm(id, e.message)
            reset()
        }
    }

    fun onDataReceived(data: ByteArray) {
        if (closed) return

        readChannel.trySend(data)
        frame.writeDataConfirm(id, 128)
    }

    fun onDialAccepted() {
        dialChannel.trySend(Unit)
    }

    fun onConnect(addr: String) {
        connectAddr = addr
        connectChannel.trySend(Unit)
    }

    fun onConnectConfirm(err: String?) {
        if (err != null) {
            error = Exception(err)
            reset()
        }
        connectChannel.trySend(Unit)
    }

    fun onDataConfirm(size: Int) {
        if (size > 0) {
            writeAble.trySend(Unit)
        } else {
            writeAble = Channel(1)
        }
        writeDone.trySend(Unit)
    }

    fun onDataWindow(size: Int) {
        if (size > 0) {
            writeAble.trySend(Unit)
        } else {
            writeAble = Channel(1)
        }
    }

    fun closeConn() {
        if (closed) return
        closed = true
        readChannel.close()
    }

    fun reset() {
        if (closed) return
        closed = true
        error = Exception("Connection reset")
        readChannel.close()
    }

    fun close() {
        if (!closed) {
            closeConn()
            frame.writeClose(id)
            frame.cleanConn(id)
        }
    }
}