package me.i996.client.mux

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.*
import java.util.concurrent.atomic.AtomicBoolean

class Stream(
    val id: Int,
    private val session: Session,
    private val scope: CoroutineScope
) {
    private val pipeIn: PipedInputStream = PipedInputStream(128 * 1024)
    private val pipeOut: PipedOutputStream = PipedOutputStream(pipeIn)
    private val ingressChannel = Channel<ByteArray>(32) // 32 frames * 32KB = 1MB max

    private val closed = AtomicBoolean(false)
    private var closeError: Throwable? = null

    init {
        scope.launch(Dispatchers.IO) { ingressLoop() }
    }

    private suspend fun ingressLoop() {
        for (data in ingressChannel) {
            try {
                withContext(Dispatchers.IO) { pipeOut.write(data) }
            } catch (e: Throwable) {
                // pipe closed; drain and exit
                for (ignored in ingressChannel) {}
                return
            }
        }
        runCatching { withContext(Dispatchers.IO) { pipeOut.close() } }
    }

    internal fun recvData(data: ByteArray) {
        val copy = data.copyOf()
        ingressChannel.trySend(copy)
    }

    internal fun recvFIN() {
        ingressChannel.close()
    }

    fun closeWithError(err: Throwable) {
        if (closed.compareAndSet(false, true)) {
            closeError = err
            ingressChannel.close(err)
            runCatching { pipeOut.close() }
            runCatching { pipeIn.close() }
        }
    }

    fun getInputStream(): InputStream = pipeIn

    fun getOutputStream(): OutputStream = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()))
        override fun write(b: ByteArray, off: Int, len: Int) {
            if (closed.get()) throw IOException(closeError ?: IOException("stream closed"))
            val data = b.copyOfRange(off, off + len)
            runBlocking { session.writeData(id, data) }
        }
    }

    fun close() {
        closeWithError(IOException("stream closed"))
        session.removeStream(id)
        scope.launch(Dispatchers.IO) {
            runCatching { session.writeFrame(Frame(id, 0x02.toByte())) }
        }
    }

    fun isClosed() = closed.get()
}
