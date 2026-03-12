package me.i996.client.mux

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.*
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "I996Session"

// Frame flags
private const val FLAG_DATA: Byte = 0x00
private const val FLAG_SYN: Byte = 0x01
private const val FLAG_FIN: Byte = 0x02
private const val FLAG_RST: Byte = 0x03
private const val FLAG_PING: Byte = 0x04
private const val FLAG_PONG: Byte = 0x05
private const val FLAG_CTRL: Byte = 0x06

private const val CTRL_STREAM_ID = 0
private const val HEADER_SIZE = 8
private const val MAX_FRAME_SIZE = 32 * 1024
private const val PING_INTERVAL_MS = 10_000L
private const val PING_TIMEOUT_MS = 30_000L

// ---- Control message types ------------------------------------------------

object MsgType {
    const val AUTH = "auth"
    const val AUTH_RESULT = "auth_result"
    const val SIGNAL = "signal"
    const val RELOAD = "reload"
    const val KICK = "kick"
    const val BREAK = "break"
    const val RESET = "reset"
}

data class CtrlMsg(val type: String, val data: JSONObject? = null) {
    fun toJSON(): JSONObject = JSONObject().apply {
        put("type", type)
        data?.let { put("data", it) }
    }

    companion object {
        fun fromJSON(json: JSONObject): CtrlMsg {
            val type = json.getString("type")
            val data = if (json.has("data")) json.getJSONObject("data") else null
            return CtrlMsg(type, data)
        }
    }
}

// ---- Frame -----------------------------------------------------------------

internal data class Frame(
    val streamId: Int,
    val flags: Byte,
    val data: ByteArray = ByteArray(0)
)

private fun readFrame(input: InputStream): Frame {
    val hdr = ByteArray(HEADER_SIZE)
    readFull(input, hdr)
    val buf = ByteBuffer.wrap(hdr)
    val sid = buf.int              // 4 bytes
    val flags = buf.get()          // 1 byte
    val b5 = buf.get().toInt() and 0xFF
    val b6 = buf.get().toInt() and 0xFF
    val b7 = buf.get().toInt() and 0xFF
    val length = (b5 shl 16) or (b6 shl 8) or b7
    val data = if (length > 0) {
        ByteArray(length).also { readFull(input, it) }
    } else ByteArray(0)
    return Frame(sid, flags, data)
}

private fun readFull(input: InputStream, buf: ByteArray) {
    var offset = 0
    while (offset < buf.size) {
        val n = input.read(buf, offset, buf.size - offset)
        if (n < 0) throw EOFException("stream closed")
        offset += n
    }
}

// ---- Session ---------------------------------------------------------------

class Session(
    private val socket: Socket,
    private val isClient: Boolean,
    private val scope: CoroutineScope
) {
    private val streams = ConcurrentHashMap<Int, Stream>()
    private val writeMutex = Mutex()
    private val nextId = AtomicInteger(if (isClient) 1 else 2)
    val acceptChannel = Channel<Stream>(256)
    private val closed = AtomicBoolean(false)
    private var closeError: Throwable? = null
    private val lastPong = AtomicLong(System.currentTimeMillis())

    private val ctrlChannel = Channel<CtrlMsg>(64)
    private val ctrlHandlers = mutableListOf<Pair<String, (CtrlMsg) -> Unit>>()
    private val ctrlHandlersMutex = Mutex()

    private val output: OutputStream = socket.getOutputStream().buffered(64 * 1024)
    private val input: InputStream = socket.getInputStream()

    init {
        scope.launch(Dispatchers.IO) { readLoop() }
        scope.launch(Dispatchers.IO) { pingLoop() }
    }

    // ---- Control channel API -----------------------------------------------

    suspend fun sendCtrl(msg: CtrlMsg) {
        val json = msg.toJSON().toString()
        writeFrame(Frame(CTRL_STREAM_ID, FLAG_CTRL, json.toByteArray()))
    }

    suspend fun sendSignal(name: String, payload: Any) {
        val signalData = JSONObject().apply {
            put("name", name)
            put("payload", payload)
        }
        val data = JSONObject().apply {
            put("name", name)
            put("payload", signalData)
        }
        sendCtrl(CtrlMsg(MsgType.SIGNAL, data))
    }

    fun onCtrl(type: String, fn: (CtrlMsg) -> Unit) {
        scope.launch {
            ctrlHandlersMutex.withLock {
                ctrlHandlers.add(type to fn)
            }
        }
    }

    fun ctrlChannel(): kotlinx.coroutines.channels.ReceiveChannel<CtrlMsg> = ctrlChannel

    // ---- Stream API --------------------------------------------------------

    suspend fun open(): Stream {
        if (closed.get()) throw IOException("session closed")
        val id = nextId.getAndAdd(2)
        val st = Stream(id, this, scope)
        streams[id] = st
        writeFrame(Frame(id, FLAG_SYN))
        return st
    }

    suspend fun accept(): Stream {
        return acceptChannel.receive()
    }

    // ---- Lifecycle ---------------------------------------------------------

    fun close() {
        runCatching { closeWithError(IOException("session closed")) }
    }
    fun isClosed() = closed.get()

    fun closeWithError(err: Throwable) {
        if (closed.compareAndSet(false, true)) {
            closeError = err
            runCatching { socket.close() }
            streams.values.forEach { it.closeWithError(err) }
            streams.clear()
            acceptChannel.close(err)
            ctrlChannel.close(err)
        }
    }

    // ---- Internal write ----------------------------------------------------

    internal suspend fun writeFrame(f: Frame) {
        if (closed.get()) throw IOException("session closed: ${closeError?.message}")
        writeMutex.withLock {
            val header = ByteArray(HEADER_SIZE)
            val bb = ByteBuffer.wrap(header)
            bb.putInt(f.streamId)
            bb.put(f.flags)
            val len = f.data.size
            header[5] = (len shr 16).toByte()
            header[6] = (len shr 8).toByte()
            header[7] = len.toByte()
            try {
                socket.soTimeout = 30_000
                output.write(header)
                if (f.data.isNotEmpty()) output.write(f.data)
                output.flush()
                socket.soTimeout = 0
            } catch (e: Throwable) {
                closeWithError(e)
                throw e
            }
        }
    }

    internal suspend fun writeData(id: Int, data: ByteArray): Int {
        var offset = 0
        while (offset < data.size) {
            val end = minOf(offset + MAX_FRAME_SIZE, data.size)
            val chunk = data.copyOfRange(offset, end)
            writeFrame(Frame(id, FLAG_DATA, chunk))
            offset = end
        }
        return data.size
    }

    internal fun removeStream(id: Int) {
        streams.remove(id)
    }

    // ---- Read loop ---------------------------------------------------------

    private suspend fun readLoop() {
        try {
            while (!closed.get()) {
                val f = withContext(Dispatchers.IO) { readFrame(input) }
                dispatch(f)
            }
        } catch (e: Throwable) {
            if (!closed.get()) {
                Log.w(TAG, "readLoop error: ${e.message}")
                closeWithError(e)
            }
        }
    }

    private suspend fun dispatch(f: Frame) {
        when (f.flags) {
            FLAG_CTRL -> dispatchCtrl(f.data)

            FLAG_SYN -> {
                val st = Stream(f.streamId, this, scope)
                streams[f.streamId] = st
                val sent = acceptChannel.trySend(st)
                if (sent.isFailure) {
                    st.closeWithError(IOException("accept queue full"))
                    writeFrame(Frame(f.streamId, FLAG_RST))
                }
            }

            FLAG_DATA -> streams[f.streamId]?.recvData(f.data)

            FLAG_FIN -> streams[f.streamId]?.recvFIN()

            FLAG_RST -> {
                streams.remove(f.streamId)?.closeWithError(IOException("stream reset"))
            }

            FLAG_PING -> writeFrame(Frame(0, FLAG_PONG))

            FLAG_PONG -> lastPong.set(System.currentTimeMillis())
        }
    }

    private suspend fun dispatchCtrl(data: ByteArray) {
        val json = runCatching { JSONObject(String(data)) }.getOrNull() ?: return
        val msg = runCatching { CtrlMsg.fromJSON(json) }.getOrNull() ?: return

        val handlers = ctrlHandlersMutex.withLock { ctrlHandlers.toList() }
        handlers.filter { it.first == msg.type }.forEach { it.second(msg) }

        ctrlChannel.trySend(msg)
    }

    // ---- Ping loop ---------------------------------------------------------

    private suspend fun pingLoop() {
        while (!closed.get()) {
            delay(PING_INTERVAL_MS)
            if (closed.get()) break
            val elapsed = System.currentTimeMillis() - lastPong.get()
            if (elapsed > PING_TIMEOUT_MS) {
                Log.w(TAG, "ping timeout")
                closeWithError(IOException("ping timeout"))
                return
            }
            runCatching { writeFrame(Frame(0, FLAG_PING)) }
                .onFailure { return }
        }
    }
}
