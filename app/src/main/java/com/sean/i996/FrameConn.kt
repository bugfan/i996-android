package com.sean.i996

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.*
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

class FrameConn(
    private val socket: Socket,
    private val isDialer: Boolean
) {
    private var lastID: Long = if (isDialer) 128L else 129L
    private val conns = ConcurrentHashMap<Long, Conn>()
    private val writeChannel = Channel<Data>(Channel.UNLIMITED)
    private val acceptChannel = Channel<Long>(128)
    private var info: Info? = null
    private val infoChannel = Channel<Unit>(1)
    private var error: Exception? = null
    private var closed = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        scope.launch { runReader() }
        scope.launch { runWriter() }
        scope.launch { supervisor() }
    }

    private fun newID(): Long = synchronized(this) {
        val id = lastID + 2
        lastID = id
        id
    }

    fun setInfo(info: Info) {
        this.info = info
        scope.launch {
            writeChannel.send(InfoData(info))
        }
    }

    suspend fun getInfo(): Info? {
        infoChannel.receive()
        return info
    }

    suspend fun accept(): Conn {
        val id = acceptChannel.receive()
        return acceptConn(id)
    }

    private fun acceptConn(id: Long): Conn {
        writeAccept(id)
        val conn = Conn(id, this)
        setConn(conn)
        return conn
    }

    private fun setConn(conn: Conn) {
        conns[conn.id] = conn
    }

    private fun getConn(id: Long): Conn? = conns[id]

    fun cleanConn(id: Long) {
        conns.remove(id)
    }

    fun close() {
        if (closed) return
        closed = true
        scope.cancel()
        try {
            socket.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private suspend fun runReader() {
        try {
            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))

            while (!closed) {
                val id = readUvarint(input)

                when {
                    id == 0L -> controlProcess(input)
                    id >= 128L -> dataProcess(id, input)
                    else -> throw Exception("Unexpected connection id $id")
                }
            }
        } catch (e: Exception) {
            error = e
            e.printStackTrace()
        }
    }

    private suspend fun controlProcess(input: DataInputStream) {
        val cmd = readUvarint(input)

        when (cmd.toInt()) {
            COMMAND_DIAL -> {
                val id = readUvarint(input)
                acceptChannel.send(id)
            }
            COMMAND_ACCEPT -> {
                val id = readUvarint(input)
                getConn(id)?.onDialAccepted()
            }
            COMMAND_CLOSE -> {
                val id = readUvarint(input)
                getConn(id)?.let {
                    cleanConn(id)
                    it.closeConn()
                }
            }
            COMMAND_RESET -> {
                val id = readUvarint(input)
                getConn(id)?.let {
                    cleanConn(id)
                    it.reset()
                }
            }
            COMMAND_DATA_CONFIRM -> {
                val id = readUvarint(input)
                val size = readUvarint(input).toInt()
                getConn(id)?.onDataConfirm(size)
            }
            COMMAND_DATA_WINDOW -> {
                val id = readUvarint(input)
                val size = readUvarint(input).toInt()
                getConn(id)?.onDataWindow(size)
            }
            COMMAND_CONNECT -> {
                val id = readUvarint(input)
                val size = readUvarint(input).toInt()
                val addr = ByteArray(size)
                input.readFully(addr)
                getConn(id)?.onConnect(String(addr))
            }
            COMMAND_CONNECT_CONFIRM -> {
                val id = readUvarint(input)
                val size = readUvarint(input).toInt()
                val err = if (size > 0) {
                    val errBytes = ByteArray(size)
                    input.readFully(errBytes)
                    String(errBytes)
                } else null
                getConn(id)?.onConnectConfirm(err)
            }
            COMMAND_INFO -> {
                val size = readUvarint(input).toInt()
                if (size > 0) {
                    val infoBytes = ByteArray(size)
                    input.readFully(infoBytes)
                    // 解析JSON格式的info数据
                    val json = String(infoBytes)
                    // 简单解析JSON中的id字段
                    val idRegex = "\"id\":\\s*\"([^\"]+)\"".toRegex()
                    val match = idRegex.find(json)
                    if (match != null) {
                        val id = match.groupValues[1]
                        info = Info(id)
                    }
                }
                // 通知等待Info的协程
                infoChannel.trySend(Unit)
            }
            COMMAND_PING -> {
                writeChannel.send(PongData())
            }
            COMMAND_PONG -> {
                lastPong = System.currentTimeMillis()
            }
        }
    }

    private suspend fun dataProcess(id: Long, input: DataInputStream) {
        val size = readUvarint(input).toInt()
        val data = ByteArray(size)
        input.readFully(data)

        getConn(id)?.onDataReceived(data)
    }

    private fun readUvarint(input: DataInputStream): Long {
        var result = 0L
        var shift = 0

        while (true) {
            val b = input.readByte().toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) break
            shift += 7
        }

        return result
    }

    private suspend fun runWriter() {
        try {
            val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))

            while (!closed) {
                val data = withTimeoutOrNull(10000) {
                    writeChannel.receive()
                }

                if (data != null) {
                    processWrite(output, data)
                    output.flush()
                }
            }
        } catch (e: Exception) {
            error = e
            e.printStackTrace()
        }
    }

    private fun processWrite(output: DataOutputStream, data: Data) {
        when (data) {
            is DataData -> {
                writeUvarint(output, data.id)
                writeUvarint(output, data.data.size.toLong())
                output.write(data.data)
            }
            is DialData -> {
                writeUvarint(output, 0L)
                writeUvarint(output, COMMAND_DIAL.toLong())
                writeUvarint(output, data.id)
            }
            is AcceptData -> {
                writeUvarint(output, 0L)
                writeUvarint(output, COMMAND_ACCEPT.toLong())
                writeUvarint(output, data.id)
            }
            is CloseData -> {
                writeUvarint(output, 0L)
                writeUvarint(output, COMMAND_CLOSE.toLong())
                writeUvarint(output, data.id)
            }
            is ResetData -> {
                writeUvarint(output, 0L)
                writeUvarint(output, COMMAND_RESET.toLong())
                writeUvarint(output, data.id)
            }
            is ConnectData -> {
                writeUvarint(output, 0L)
                writeUvarint(output, COMMAND_CONNECT.toLong())
                writeUvarint(output, data.id)
                writeUvarint(output, data.addr.size.toLong())
                output.write(data.addr)
            }
            is ConnectConfirmData -> {
                writeUvarint(output, 0L)
                writeUvarint(output, COMMAND_CONNECT_CONFIRM.toLong())
                writeUvarint(output, data.id)
                writeUvarint(output, data.err.size.toLong())
                if (data.err.isNotEmpty()) {
                    output.write(data.err)
                }
            }
            is DataConfirmData -> {
                writeUvarint(output, 0L)
                writeUvarint(output, COMMAND_DATA_CONFIRM.toLong())
                writeUvarint(output, data.id)
                writeUvarint(output, data.size.toLong())
            }
            is DataWindowData -> {
                writeUvarint(output, 0L)
                writeUvarint(output, COMMAND_DATA_WINDOW.toLong())
                writeUvarint(output, data.id)
                writeUvarint(output, data.size.toLong())
            }
            is PongData -> {
                writeUvarint(output, 0L)
                writeUvarint(output, COMMAND_PONG.toLong())
            }
            is PingData -> {
                writeUvarint(output, 0L)
                writeUvarint(output, COMMAND_PING.toLong())
            }
            is InfoData -> {
                val json = """{"id":"${data.info.id}"}""".toByteArray()
                println("Writing INFO: ${String(json)}")
                writeUvarint(output, 0L)
                writeUvarint(output, COMMAND_INFO.toLong())
                writeUvarint(output, json.size.toLong())
                output.write(json)
            }
        }
    }

    private fun writeUvarint(output: DataOutputStream, value: Long) {
        var v = value
        while (v >= 0x80) {
            output.writeByte(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        output.writeByte(v.toInt())
    }

      @Volatile private var lastPing = System.currentTimeMillis()
    @Volatile private var lastPong = System.currentTimeMillis()

    private suspend fun supervisor() {
        while (!closed) {
            delay(5000)
            val now = System.currentTimeMillis()

            // 检查pong超时
            if (now - lastPong > 15000) {
                println("Pong timeout, closing connection")
                error = Exception("Pong timeout")
                close()
                return
            }

            // 发送ping
            writeChannel.send(PingData())
            lastPing = now
        }
    }

    fun writeAccept(id: Long) {
        scope.launch { writeChannel.send(AcceptData(id)) }
    }

    fun writeData(id: Long, data: ByteArray) {
        scope.launch { writeChannel.send(DataData(id, data)) }
    }

    fun writeConnect(id: Long, addr: String) {
        scope.launch { writeChannel.send(ConnectData(id, addr.toByteArray())) }
    }

    fun writeConnectConfirm(id: Long, err: String?) {
        scope.launch {
            writeChannel.send(ConnectConfirmData(id, err?.toByteArray() ?: byteArrayOf()))
        }
    }

    fun writeDataConfirm(id: Long, size: Int) {
        scope.launch { writeChannel.send(DataConfirmData(id, size)) }
    }

    fun writeClose(id: Long) {
        scope.launch { writeChannel.send(CloseData(id)) }
    }

    fun writeReset(id: Long) {
        scope.launch { writeChannel.send(ResetData(id)) }
    }

    companion object {
        const val COMMAND_DATA = 0
        const val COMMAND_DATA_CONFIRM = 1
        const val COMMAND_DATA_WINDOW = 2
        const val COMMAND_CONNECT = 3
        const val COMMAND_CONNECT_CONFIRM = 4
        const val COMMAND_DIAL = 128
        const val COMMAND_ACCEPT = 129
        const val COMMAND_CLOSE = 130
        const val COMMAND_RESET = 131
        const val COMMAND_PING = 132
        const val COMMAND_PONG = 133
        const val COMMAND_INFO = 137
    }
}