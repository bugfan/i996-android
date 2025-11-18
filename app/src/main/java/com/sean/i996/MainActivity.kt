package com.sean.i996

import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import java.io.*
import java.net.Socket
import java.security.cert.X509Certificate
import javax.net.ssl.*

class MainActivity : AppCompatActivity() {
    private lateinit var btnConnect: Button
    private lateinit var btnDisconnect: Button
    private lateinit var etServerAddr: EditText
    private lateinit var etClientId: EditText
    private lateinit var tvStatus: TextView

    private var tunnelClient: TunnelClient? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnConnect = findViewById(R.id.btnConnect)
        btnDisconnect = findViewById(R.id.btnDisconnect)
        etServerAddr = findViewById(R.id.etServerAddr)
        etClientId = findViewById(R.id.etClientId)
        tvStatus = findViewById(R.id.tvStatus)

        // 设置默认值
        etServerAddr.setText("192.168.1.130:3333") // Android 模拟器访问主机
        etClientId.setText("testid")

        btnConnect.setOnClickListener {
            val serverAddr = etServerAddr.text.toString()
            val clientId = etClientId.text.toString()

            if (serverAddr.isNotEmpty() && clientId.isNotEmpty()) {
                connectToServer(serverAddr, clientId)
            } else {
                updateStatus("请输入服务器地址和客户端ID")
            }
        }

        btnDisconnect.setOnClickListener {
            disconnectFromServer()
        }

        btnDisconnect.isEnabled = false
    }

    private fun connectToServer(serverAddr: String, clientId: String) {
        scope.launch {
            try {
                withContext(Dispatchers.Main) {
                    btnConnect.isEnabled = false
                }

                updateStatus("正在连接到 $serverAddr...")

                tunnelClient = TunnelClient(serverAddr, clientId) { status ->
                    updateStatus(status)
                }

                withContext(Dispatchers.Main) {
                    btnDisconnect.isEnabled = true
                }

                tunnelClient?.connect()

            } catch (e: Exception) {
                Log.e(TAG, "连接失败", e)
                updateStatus("连接失败: ${e.message}")
                withContext(Dispatchers.Main) {
                    btnConnect.isEnabled = true
                    btnDisconnect.isEnabled = false
                }
            }
        }
    }

    private fun disconnectFromServer() {
        scope.launch {
            try {
                tunnelClient?.disconnect()
                tunnelClient = null
                updateStatus("已断开连接")
                withContext(Dispatchers.Main) {
                    btnConnect.isEnabled = true
                    btnDisconnect.isEnabled = false
                }
            } catch (e: Exception) {
                Log.e(TAG, "断开连接失败", e)
                updateStatus("断开失败: ${e.message}")
            }
        }
    }

    private fun updateStatus(status: String) {
        runOnUiThread {
            tvStatus.text = status
            Log.d(TAG, status)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        tunnelClient?.disconnect()
    }

    companion object {
        private const val TAG = "TunnelClient"
    }
}

// 隧道客户端类
class TunnelClient(
    private val serverAddr: String,
    private val clientId: String,
    private val statusCallback: (String) -> Unit
) {
    private var frameConn: FrameConn? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    suspend fun connect() {
        while (true) {
            try {
                statusCallback("正在连接服务器 $serverAddr...")

                val parts = serverAddr.split(":")
                val host = parts[0]
                val port = parts[1].toInt()

                // 创建 TLS 连接
                val socket = Socket(host, port)

                statusCallback("TCP 连接已建立")

                val sslContext = createTrustAllSSLContext()
                val sslSocket = sslContext.socketFactory.createSocket(
                    socket, host, port, true
                ) as SSLSocket

                sslSocket.startHandshake()

                statusCallback("TLS 握手完成")

                // 立即发送 INFO，在创建 FrameConn 之前
                try {
                    val output = BufferedOutputStream(sslSocket.getOutputStream())
                    val json = """{"ID":"$clientId"}"""
                    val infoBytes = json.toByteArray()

                    Log.d("TunnelClient", "准备发送 INFO:")
                    Log.d("TunnelClient", "  JSON: $json")
                    Log.d("TunnelClient", "  JSON bytes length: ${infoBytes.size}")
                    Log.d("TunnelClient", "  JSON bytes: ${infoBytes.joinToString(",") { (it.toInt() and 0xFF).toString() }}")

                    // 控制ID = 0
                    val controlId = encodeUvarint(0)
                    Log.d("TunnelClient", "  Control ID (0): ${controlId.joinToString(",")}")
                    output.write(controlId)

                    // INFO 命令 = 136
                    val infoCmd = encodeUvarint(136)
                    Log.d("TunnelClient", "  INFO command (136): ${infoCmd.joinToString(",")}")
                    output.write(infoCmd)

                    // 数据长度
                    val lengthBytes = encodeUvarint(infoBytes.size.toLong())
                    Log.d("TunnelClient", "  Length (${infoBytes.size}): ${lengthBytes.joinToString(",")}")
                    output.write(lengthBytes)

                    // 数据内容
                    Log.d("TunnelClient", "  Data: ${infoBytes.joinToString(",") { (it.toInt() and 0xFF).toString() }}")
                    output.write(infoBytes)

                    output.flush()

                    Log.d("TunnelClient", "INFO 已完整发送并 flush")
                    statusCallback("INFO 已提前发送: $clientId")
                } catch (e: Exception) {
                    Log.e("TunnelClient", "发送 INFO 失败", e)
                    throw e
                }

                // 等待让 INFO 到达服务器并被处理
                delay(500)

                // 现在创建 FrameConn
                frameConn = FrameConn(sslSocket, true)

                statusCallback("FrameConn 已创建")

                // 等待确认连接成功
                delay(500)

                statusCallback("隧道已建立，等待代理请求...")

                // 开始接受连接
                acceptLoop()

            } catch (e: Exception) {
                Log.e("TunnelClient", "连接错误", e)
                statusCallback("连接错误: ${e.message}，10秒后重试...")
                delay(10000)
            }
        }
    }

    // 编码 uvarint 到字节数组
    private fun encodeUvarint(value: Long): ByteArray {
        val result = mutableListOf<Byte>()
        var v = value
        while (v >= 0x80) {
            result.add(((v and 0x7F) or 0x80).toByte())
            v = v shr 7
        }
        result.add(v.toByte())
        return result.toByteArray()
    }

    private suspend fun acceptLoop() {
        while (true) {
            try {
                val conn = frameConn?.accept() ?: break
                statusCallback("接受新连接 ID: ${conn.id}")

                // 为每个连接启动代理
                scope.launch {
                    try {
                        conn.proxy()
                    } catch (e: Exception) {
                        Log.e("TunnelClient", "代理错误", e)
                    }
                }
            } catch (e: Exception) {
                Log.e("TunnelClient", "接受连接错误", e)
                statusCallback("隧道关闭，正在重试...")
                break
            }
        }
    }

    fun disconnect() {
        frameConn?.close()
        scope.cancel()
    }

    private fun createTrustAllSSLContext(): SSLContext {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustAllCerts, java.security.SecureRandom())
        return sslContext
    }
}

// 信息类
data class Info(val id: String)

// FrameConn 类 - 帧连接
class FrameConn(
    private val socket: Socket,
    private val isDialer: Boolean
) {
    private val reader = BufferedInputStream(socket.getInputStream())
    private val writer = BufferedOutputStream(socket.getOutputStream())

    private var lastId: Long = if (isDialer) 128 else 129
    private val conns = mutableMapOf<Long, Conn>()
    private val lock = Any()

    private val acceptChannel = kotlinx.coroutines.channels.Channel<Long>(128)
    private val writeChannel = kotlinx.coroutines.channels.Channel<Data>(256)

    private var info: Info? = null
    private val infoChannel = kotlinx.coroutines.channels.Channel<Unit>(1)
    private var infoReceived = false

    private var closed = false
    private var error: Exception? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        Log.d("FrameConn", "FrameConn 初始化开始, isDialer=$isDialer, lastId=$lastId")
        // 启动读写协程
        scope.launch { runWriter() }
        scope.launch { runReader() }
        scope.launch { supervisor() }

        Log.d("FrameConn", "FrameConn 初始化完成")
    }

    fun setInfo(info: Info) {
        synchronized(lock) {
            this.info = info
        }
        Log.d("FrameConn", "设置 Info: ${info.id}, 准备同步发送")

        // 立即同步发送，不使用协程！
        val json = """{"ID":"${info.id}"}"""
        val infoData = InfoData(json.toByteArray())

        // 直接写入，不经过 channel
        try {
            synchronized(writer) {
                writeUvarint(0)
                writeUvarint(COMMAND_INFO)
                writeUvarint(infoData.info.size.toLong())
                writer.write(infoData.info)
                writer.flush()
            }
            Log.d("FrameConn", "Info 已同步发送: $json")
        } catch (e: Exception) {
            Log.e("FrameConn", "发送 Info 失败", e)
            close()
        }
    }

    suspend fun accept(): Conn {
        val id = acceptChannel.receive()
        return acceptConn(id)
    }

    private fun acceptConn(id: Long): Conn {
        writeAccept(id)
        val conn = newConnWithId(id)
        setConn(conn)
        return conn
    }

    private fun newId(): Long {
        synchronized(lock) {
            lastId += 2
            return lastId
        }
    }

    private fun newConnWithId(id: Long): Conn {
        return Conn(id, this)
    }

    private fun setConn(conn: Conn) {
        synchronized(lock) {
            conns[conn.id] = conn
        }
    }

    fun getConn(id: Long): Conn? {
        synchronized(lock) {
            return conns[id]
        }
    }

    fun cleanConn(id: Long) {
        synchronized(lock) {
            conns.remove(id)
        }
    }

    fun close() {
        if (!closed) {
            closed = true
            writeTunnelClose()
            cleanAllConns()
            scope.cancel()
            socket.close()
        }
    }

    private fun cleanAllConns() {
        synchronized(lock) {
            conns.values.forEach { it.reset() }
            conns.clear()
        }
    }

    // 读取协程
    private suspend fun runReader() {
        try {
            Log.d("FrameConn", "Reader 已启动")

            while (!closed) {
                val id = readUvarint()
                Log.d("FrameConn", "读取到 ID: $id")

                when {
                    id == 0L -> processControl()
                    id >= 128L -> processData(id)
                    else -> throw IOException("Unexpected connection id: $id")
                }
            }
        } catch (e: Exception) {
            Log.e("FrameConn", "读取器错误", e)
            error = e
            close()
        }
    }

    // 写入协程
    private suspend fun runWriter() {
        try {
            Log.d("FrameConn", "Writer 已启动")

            while (!closed) {
                withTimeoutOrNull(5000) {
                    val data = writeChannel.receive()
                    processWrite(data)
                }

                // 定期发送 ping
                if (!closed) {
                    processWrite(PingData())
                }
            }
        } catch (e: Exception) {
            Log.e("FrameConn", "写入器错误", e)
            error = e
            close()
        }
    }

    // 监督协程
    private suspend fun supervisor() {
        while (!closed) {
            delay(15000)
            // 可以添加超时检测逻辑
        }
    }

    // 处理控制命令
    private suspend fun processControl() {
        val cmd = readUvarint()
        Log.d("FrameConn", "收到控制命令: $cmd")

        when (cmd) {
            COMMAND_DIAL -> {
                val id = readUvarint()
                Log.d("FrameConn", "收到 DIAL: id=$id")
                acceptChannel.send(id)
            }
            COMMAND_ACCEPT -> {
                val id = readUvarint()
                Log.d("FrameConn", "收到 ACCEPT: id=$id")
                getConn(id)?.onDialAccepted()
            }
            COMMAND_CLOSE -> {
                val id = readUvarint()
                Log.d("FrameConn", "收到 CLOSE: id=$id")
                getConn(id)?.closeConn()
                cleanConn(id)
            }
            COMMAND_RESET -> {
                val id = readUvarint()
                Log.d("FrameConn", "收到 RESET: id=$id")
                getConn(id)?.reset()
                cleanConn(id)
            }
            COMMAND_DATA_CONFIRM -> {
                val id = readUvarint()
                val size = readUvarint()
                Log.d("FrameConn", "收到 DATA_CONFIRM: id=$id, size=$size")
                getConn(id)?.onDataConfirm(size.toInt())
            }
            COMMAND_DATA_WINDOW -> {
                val id = readUvarint()
                val size = readUvarint()
                Log.d("FrameConn", "收到 DATA_WINDOW: id=$id, size=$size")
                getConn(id)?.onDataWindow(size.toInt())
            }
            COMMAND_CONNECT -> {
                val id = readUvarint()
                val addrLen = readUvarint()
                val addrBytes = ByteArray(addrLen.toInt())
                reader.read(addrBytes)
                val addr = String(addrBytes)
                Log.d("FrameConn", "收到 CONNECT: id=$id, addr=$addr")
                getConn(id)?.onConnect(addr)
            }
            COMMAND_CONNECT_CONFIRM -> {
                val id = readUvarint()
                val errLen = readUvarint()
                val err = if (errLen > 0) {
                    val errBytes = ByteArray(errLen.toInt())
                    reader.read(errBytes)
                    String(errBytes)
                } else null
                Log.d("FrameConn", "收到 CONNECT_CONFIRM: id=$id, err=$err")
                getConn(id)?.onConnectConfirm(err)
            }
            COMMAND_PING -> {
                Log.d("FrameConn", "收到 PING")
                writePong()
            }
            COMMAND_PONG -> {
                Log.d("FrameConn", "收到 PONG")
            }
            COMMAND_TUNNEL_CLOSE -> {
                Log.d("FrameConn", "收到 TUNNEL_CLOSE")
                cleanAllConns()
                writeTunnelCloseConfirm()
                close()
            }
            COMMAND_INFO -> {
                val size = readUvarint()
                Log.d("FrameConn", "收到 INFO 命令, size=$size")
                if (size > 0) {
                    val infoBytes = ByteArray(size.toInt())
                    var readed = 0
                    while (readed < size) {
                        val n = reader.read(infoBytes, readed, size.toInt() - readed)
                        if (n < 0) throw EOFException()
                        readed += n
                    }
                    val infoStr = String(infoBytes)
                    Log.d("FrameConn", "收到 INFO 数据: $infoStr")

                    // 解析 JSON 并保存
                    try {
                        // 简单解析 {"ID":"xxx"}
                        val idStart = infoStr.indexOf("\"ID\":\"") + 6
                        val idEnd = infoStr.indexOf("\"", idStart)
                        val id = infoStr.substring(idStart, idEnd)

                        synchronized(lock) {
                            this.info = Info(id)
                            this.infoReceived = true
                        }

                        Log.d("FrameConn", "Info 已保存: id=$id")

                        // 通知等待的协程
                        infoChannel.trySend(Unit)
                    } catch (e: Exception) {
                        Log.e("FrameConn", "解析 Info 失败", e)
                    }
                }
            }
        }
    }

    // 处理数据
    private suspend fun processData(id: Long) {
        val size = readUvarint()
        val data = ByteArray(size.toInt())
        var readed = 0

        while (readed < size) {
            val n = reader.read(data, readed, size.toInt() - readed)
            if (n < 0) throw EOFException()
            readed += n
        }

        getConn(id)?.onDataReceived(data)
    }

    // 处理写入
    private fun processWrite(data: Data) {
        try {
            when (data) {
                is DataData -> {
                    writeUvarint(data.id)
                    writeUvarint(data.data.size.toLong())
                    writer.write(data.data)
                    Log.d("FrameConn", "发送 DATA: id=${data.id}, size=${data.data.size}")
                }
                is DialData -> {
                    writeUvarint(0)
                    writeUvarint(COMMAND_DIAL)
                    writeUvarint(data.id)
                    Log.d("FrameConn", "发送 DIAL: id=${data.id}")
                }
                is AcceptData -> {
                    writeUvarint(0)
                    writeUvarint(COMMAND_ACCEPT)
                    writeUvarint(data.id)
                    Log.d("FrameConn", "发送 ACCEPT: id=${data.id}")
                }
                is CloseData -> {
                    writeUvarint(0)
                    writeUvarint(COMMAND_CLOSE)
                    writeUvarint(data.id)
                    Log.d("FrameConn", "发送 CLOSE: id=${data.id}")
                }
                is ResetData -> {
                    writeUvarint(0)
                    writeUvarint(COMMAND_RESET)
                    writeUvarint(data.id)
                    Log.d("FrameConn", "发送 RESET: id=${data.id}")
                }
                is ConnectData -> {
                    writeUvarint(0)
                    writeUvarint(COMMAND_CONNECT)
                    writeUvarint(data.id)
                    writeUvarint(data.addr.length.toLong())
                    writer.write(data.addr.toByteArray())
                    Log.d("FrameConn", "发送 CONNECT: id=${data.id}, addr=${data.addr}")
                }
                is ConnectConfirmData -> {
                    writeUvarint(0)
                    writeUvarint(COMMAND_CONNECT_CONFIRM)
                    writeUvarint(data.id)
                    val errBytes = data.err?.toByteArray() ?: ByteArray(0)
                    writeUvarint(errBytes.size.toLong())
                    if (errBytes.isNotEmpty()) {
                        writer.write(errBytes)
                    }
                    Log.d("FrameConn", "发送 CONNECT_CONFIRM: id=${data.id}, err=${data.err}")
                }
                is DataConfirmData -> {
                    writeUvarint(0)
                    writeUvarint(COMMAND_DATA_CONFIRM)
                    writeUvarint(data.id)
                    writeUvarint(data.size.toLong())
                    Log.d("FrameConn", "发送 DATA_CONFIRM: id=${data.id}, size=${data.size}")
                }
                is DataWindowData -> {
                    writeUvarint(0)
                    writeUvarint(COMMAND_DATA_WINDOW)
                    writeUvarint(data.id)
                    writeUvarint(data.size.toLong())
                    Log.d("FrameConn", "发送 DATA_WINDOW: id=${data.id}, size=${data.size}")
                }
                is PingData -> {
                    writeUvarint(0)
                    writeUvarint(COMMAND_PING)
                    // Log.d("FrameConn", "发送 PING")
                }
                is PongData -> {
                    writeUvarint(0)
                    writeUvarint(COMMAND_PONG)
                    Log.d("FrameConn", "发送 PONG")
                }
                is TunnelCloseData -> {
                    writeUvarint(0)
                    writeUvarint(COMMAND_TUNNEL_CLOSE)
                    Log.d("FrameConn", "发送 TUNNEL_CLOSE")
                }
                is TunnelCloseConfirmData -> {
                    writeUvarint(0)
                    writeUvarint(COMMAND_TUNNEL_CLOSE_CONFIRM)
                    Log.d("FrameConn", "发送 TUNNEL_CLOSE_CONFIRM")
                }
                is InfoData -> {
                    writeUvarint(0)
                    writeUvarint(COMMAND_INFO)
                    writeUvarint(data.info.size.toLong())
                    writer.write(data.info)
                    Log.d("FrameConn", "发送 INFO: ${String(data.info)}")
                }
            }
            writer.flush()
        } catch (e: Exception) {
            Log.e("FrameConn", "写入错误", e)
            throw e
        }
    }

    // 读取 uvarint
    private fun readUvarint(): Long {
        var result = 0L
        var shift = 0

        while (true) {
            val b = reader.read()
            if (b < 0) throw EOFException()

            result = result or ((b and 0x7F).toLong() shl shift)

            if (b and 0x80 == 0) break
            shift += 7
        }

        return result
    }

    // 写入 uvarint
    private fun writeUvarint(value: Long) {
        synchronized(writer) {
            var v = value
            while (v >= 0x80) {
                writer.write(((v and 0x7F) or 0x80).toInt())
                v = v shr 7
            }
            writer.write(v.toInt())
        }
    }

    // 写入方法
    fun writeData(id: Long, data: ByteArray): Int {
        runBlocking {
            writeChannel.send(DataData(id, data))
        }
        return data.size
    }

    fun writeConnect(id: Long, addr: String) {
        runBlocking {
            writeChannel.send(ConnectData(id, addr))
        }
    }

    fun writeConnectConfirm(id: Long, err: String?) {
        runBlocking {
            writeChannel.send(ConnectConfirmData(id, err))
        }
    }

    private fun writeAccept(id: Long) {
        runBlocking {
            writeChannel.send(AcceptData(id))
        }
    }

    fun writeClose(id: Long) {
        runBlocking {
            writeChannel.send(CloseData(id))
        }
    }

    fun writeReset(id: Long) {
        runBlocking {
            writeChannel.send(ResetData(id))
        }
    }

    private fun writePong() {
        runBlocking {
            writeChannel.send(PongData())
        }
    }

    private fun writeTunnelClose() {
        runBlocking {
            writeChannel.send(TunnelCloseData())
        }
    }

    private fun writeTunnelCloseConfirm() {
        runBlocking {
            writeChannel.send(TunnelCloseConfirmData())
        }
    }

    fun writeDataConfirm(id: Long, window: Int) {
        runBlocking {
            writeChannel.send(DataConfirmData(id, window))
        }
    }

    companion object {
        const val COMMAND_DATA = 0L
        const val COMMAND_DATA_CONFIRM = 1L
        const val COMMAND_DATA_WINDOW = 2L
        const val COMMAND_CONNECT = 3L
        const val COMMAND_CONNECT_CONFIRM = 4L
        const val COMMAND_DIAL = 128L
        const val COMMAND_ACCEPT = 129L
        const val COMMAND_CLOSE = 130L
        const val COMMAND_RESET = 131L
        const val COMMAND_PING = 132L
        const val COMMAND_PONG = 133L
        const val COMMAND_TUNNEL_CLOSE = 134L
        const val COMMAND_TUNNEL_CLOSE_CONFIRM = 135L
        const val COMMAND_INFO = 136L
    }
}

// Conn 类 - 单个连接
class Conn(
    val id: Long,
    private val frame: FrameConn
) {
    private val buffer = ByteArrayOutputStream()
    private val readChannel = kotlinx.coroutines.channels.Channel<ByteArray>(128)
    private val dialChannel = kotlinx.coroutines.channels.Channel<Unit>(1)
    private val connectChannel = kotlinx.coroutines.channels.Channel<String>(1)
    private val connectConfirmChannel = kotlinx.coroutines.channels.Channel<String?>(1)

    private var closed = false
    private var writeAble = true
    private var connectAddr: String? = null
    private var readChannelSize = 0 // 跟踪 channel 中的元素数量

    suspend fun proxy() {
        // 等待连接地址
        connectAddr = connectChannel.receive()

        Log.d("Conn", "代理连接到: $connectAddr")

        try {
            // 连接到目标地址
            val parts = connectAddr!!.split(":")
            val host = parts[0]
            val port = parts[1].toInt()

            val targetSocket = Socket(host, port)

            // 发送连接确认
            frame.writeConnectConfirm(id, null)

            Log.d("Conn", "代理连接成功: $connectAddr")

            // 开始双向转发
            coroutineScope {
                launch {
                    // 从目标读取，写入隧道
                    val input = targetSocket.getInputStream()
                    val buf = ByteArray(32 * 1024)

                    try {
                        while (!closed) {
                            val n = input.read(buf)
                            if (n < 0) break

                            val data = buf.copyOf(n)
                            write(data)
                        }
                    } catch (e: Exception) {
                        Log.e("Conn", "读取目标失败", e)
                    } finally {
                        targetSocket.close()
                        close()
                    }
                }

                launch {
                    // 从隧道读取，写入目标
                    val output = targetSocket.getOutputStream()

                    try {
                        while (!closed) {
                            val data = read()
                            output.write(data)
                            output.flush()
                        }
                    } catch (e: Exception) {
                        Log.e("Conn", "写入目标失败", e)
                    } finally {
                        targetSocket.close()
                        close()
                    }
                }
            }

        } catch (e: Exception) {
            Log.e("Conn", "代理连接失败: $connectAddr", e)
            frame.writeConnectConfirm(id, e.message)
            reset()
        }
    }

    suspend fun read(): ByteArray {
        val data = readChannel.receive()
        readChannelSize--
        return data
    }

    fun write(data: ByteArray): Int {
        if (closed) return 0

        // 等待可写
        while (!writeAble && !closed) {
            Thread.sleep(10)
        }

        return frame.writeData(id, data)
    }

    fun onDataReceived(data: ByteArray) {
        if (closed) return

        runBlocking {
            readChannel.send(data)
            readChannelSize++

            // 发送确认
            val available = 128 - readChannelSize
            frame.writeDataConfirm(id, available)
        }
    }

    fun onDialAccepted() {
        runBlocking {
            dialChannel.send(Unit)
        }
    }

    fun onConnect(addr: String) {
        runBlocking {
            connectChannel.send(addr)
        }
    }

    fun onConnectConfirm(err: String?) {
        runBlocking {
            connectConfirmChannel.send(err)
        }
    }

    fun onDataConfirm(size: Int) {
        writeAble = size > 0
    }

    fun onDataWindow(size: Int) {
        writeAble = size > 0
    }

    fun closeConn() {
        if (!closed) {
            closed = true
            readChannel.close()
        }
    }

    fun reset() {
        if (!closed) {
            closed = true
            readChannel.close()
        }
    }

    fun close() {
        if (!closed) {
            closeConn()
            frame.writeClose(id)
            frame.cleanConn(id)
        }
    }
}

// 数据类
sealed class Data

data class DataData(val id: Long, val data: ByteArray) : Data()
data class DialData(val id: Long) : Data()
data class AcceptData(val id: Long) : Data()
data class CloseData(val id: Long) : Data()
data class ResetData(val id: Long) : Data()
data class ConnectData(val id: Long, val addr: String) : Data()
data class ConnectConfirmData(val id: Long, val err: String?) : Data()
data class DataConfirmData(val id: Long, val size: Int) : Data()
data class DataWindowData(val id: Long, val size: Int) : Data()
class PingData : Data()
class PongData : Data()
class TunnelCloseData : Data()
class TunnelCloseConfirmData : Data()
data class InfoData(val info: ByteArray) : Data()

// Select 辅助函数
suspend fun <T> select(block: suspend SelectBuilder<T>.() -> Unit): T {
    val builder = SelectBuilder<T>()
    builder.block()
    return builder.await()
}

class SelectBuilder<T> {
    private var result: T? = null
    private val jobs = mutableListOf<Job>()

    fun <R> kotlinx.coroutines.channels.Channel<R>.onReceive(handler: suspend (R) -> T) {
        val job = CoroutineScope(Dispatchers.IO).launch {
            val value = this@onReceive.receive()
            result = handler(value)
        }
        jobs.add(job)
    }

    suspend fun await(): T {
        jobs.firstOrNull()?.join()
        return result!!
    }
}