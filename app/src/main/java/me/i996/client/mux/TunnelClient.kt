package me.i996.client.mux

import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.*
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.io.ByteArrayInputStream

private const val TAG = "I996Client"

data class AuthResult(
    val ok: Boolean,
    val msg: String = "",
    val map: Map<String, String> = emptyMap()
)

class TunnelClient(
    private val serverAddr: String,
    private val token: String,
    private val caPem: String,
    private val version: String = "2026.3.8",
    private val onLog: (String) -> Unit = {},
    private val onAuthResult: (AuthResult) -> Unit = {},
    private val onDisconnected: () -> Unit = {},
    private val onConnected: () -> Unit = {}
) {
    private val running = AtomicBoolean(false)
    private var currentSession: Session? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun start() {
        if (running.compareAndSet(false, true)) {
            scope.launch { reconnectLoop() }
        }
    }

    fun stop() {
        running.set(false)
        runCatching { currentSession?.close() }
        scope.cancel()
        onDisconnected()
    }

    fun isRunning() = running.get()

    private suspend fun reconnectLoop() {
        var backoff = 2_000L
        val maxBackoff = 120_000L

        while (running.get()) {
            try {
                run()
                // Normal exit (e.g. auth rejected calling os.Exit equivalent → we just stop)
                backoff = 5_000L
            } catch (e: CancellationException) {
                break
            } catch (e: Throwable) {
                if (!running.get()) break
                val msg = e.message ?: "unknown error"
                if (msg.contains("connection refused")) {
                    onLog("连接失败，${backoff / 1000}s 后重试")
                } else {
                    onLog("$msg，${backoff / 1000}s 后重试")
                }
                onDisconnected()
                delay(backoff)
                backoff = minOf(backoff * 2, maxBackoff)
            }
        }
    }

    private suspend fun run() {
        val socket = connectTLS()
        val sessionScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val session = Session(socket, isClient = true, scope = sessionScope)
        currentSession = session

        try {
            // Register control handlers before sending auth
            val authResultDeferred = CompletableDeferred<AuthResult>()

            session.onCtrl(MsgType.AUTH_RESULT) { msg ->
                val data = msg.data ?: return@onCtrl
                val ok = data.optBoolean("ok", false)
                val msgStr = data.optString("msg", "")
                val mapObj = data.optJSONObject("map")
                val map = mutableMapOf<String, String>()
                mapObj?.keys()?.forEach { key -> map[key] = mapObj.getString(key) }
                authResultDeferred.complete(AuthResult(ok, msgStr, map))
            }

            session.onCtrl(MsgType.KICK) { msg ->
                val reason = msg.data?.optString("reason", "") ?: ""
                onLog("被服务器踢出: $reason")
                session.close()
            }

            session.onCtrl(MsgType.RELOAD) { _ ->
                onLog("服务器要求重连")
                session.close()
            }

            session.onCtrl(MsgType.RESET) { msg ->
                val reason = msg.data?.optString("reason", "") ?: ""
                if (reason.isNotEmpty()) onLog(reason)
                session.close()
            }

            session.onCtrl(MsgType.BREAK) { msg ->
                val reason = msg.data?.optString("reason", "") ?: ""
                onLog(reason)
                stop()
            }

            // Send auth
            val metaJson = JSONObject().apply { put("version", version) }
            val authData = JSONObject().apply {
                put("token", token)
                put("meta", metaJson)
            }
            session.sendCtrl(CtrlMsg(MsgType.AUTH, authData))

            // Wait for auth result (15s timeout)
            val authResult = withTimeoutOrNull(15_000L) { authResultDeferred.await() }
                ?: throw IOException("auth timeout")

            if (!authResult.ok) {
                onAuthResult(authResult)
                onLog("Token 认证失败: ${authResult.msg}")
                // Don't reconnect on auth failure
                stop()
                return
            }

            onAuthResult(authResult)
            onConnected()
            onLog("连接成功 ✓")

            // Serve incoming streams
            while (running.get() && !session.isClosed()) {
                val st = try {
                    session.accept()
                } catch (e: Throwable) {
                    if (!session.isClosed()) onLog("Accept error: ${e.message}")
                    break
                }
                sessionScope.launch { handleStream(st) }
            }
        } finally {
            session.close()
            sessionScope.cancel()
            currentSession = null
            if (running.get()) onDisconnected()
        }
    }

    private suspend fun handleStream(stream: Stream) {
        try {
            val addr = readToken(stream.getInputStream())
                .trim()

            val target = try {
                withContext(Dispatchers.IO) {
                    Socket().apply {
                        connect(parseAddr(addr), 30_000)
                    }
                }
            } catch (e: Throwable) {
                writeToken(stream.getOutputStream(), "ERR ${e.message}")
                onLog("dial $addr failed: ${e.message}")
                stream.close()
                return
            }

            writeToken(stream.getOutputStream(), "OK")

            // Pipe with HTTP sniffing for logging
            pipeWithSniff(stream, target, addr)
        } catch (e: Throwable) {
            Log.d(TAG, "handleStream error: ${e.message}")
        } finally {
            stream.close()
        }
    }

    private suspend fun pipeWithSniff(stream: Stream, socket: Socket, addr: String) {
        val streamIn = stream.getInputStream()
        val streamOut = stream.getOutputStream()
        val targetIn = socket.getInputStream()
        val targetOut = socket.getOutputStream()

        val job1 = scope.launch(Dispatchers.IO) {
            // From target → stream (no sniff)
            try {
                val buf = ByteArray(32 * 1024)
                while (true) {
                    val n = targetIn.read(buf)
                    if (n < 0) break
                    streamOut.write(buf, 0, n)
                }
            } catch (_: Throwable) {}
        }

        val job2 = scope.launch(Dispatchers.IO) {
            // From stream → target (sniff first packet)
            try {
                val buf = ByteArray(32 * 1024)
                var first = true
                while (true) {
                    val n = streamIn.read(buf)
                    if (n < 0) break
                    if (first) {
                        first = false
                        logHttpRequest(addr, buf, n)
                    }
                    targetOut.write(buf, 0, n)
                }
            } catch (_: Throwable) {}
        }

        job1.join()
        job2.join()
        runCatching { socket.close() }
    }

    private fun logHttpRequest(addr: String, buf: ByteArray, n: Int) {
        val data = buf.copyOf(n)
        val newlineIdx = data.indexOf('\n'.code.toByte())
        if (newlineIdx > 0) {
            var firstLine = String(data, 0, newlineIdx).trimEnd('\r')
            val spaceIdx = firstLine.indexOf(' ')
            if (spaceIdx > 0) {
                val method = firstLine.substring(0, spaceIdx)
                val httpMethods = setOf("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS", "CONNECT", "TRACE")
                if (method in httpMethods) {
                    onLog("-> [$method] $addr ${firstLine.substring(spaceIdx + 1)}")
                    return
                }
            }
        }
        onLog("-> [HTTPS] $addr")
    }

    private fun connectTLS(): Socket {
        val (host, port) = parseHostPort(serverAddr)
        val sslContext = buildSSLContext(caPem)
        val socket = sslContext.socketFactory.createSocket(host, port) as SSLSocket
        socket.startHandshake()
        return socket
    }

    private fun buildSSLContext(caPem: String): SSLContext {
        val cf = CertificateFactory.getInstance("X.509")
        val cert = cf.generateCertificate(ByteArrayInputStream(caPem.toByteArray()))
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("ca", cert)
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore)
        }
        return SSLContext.getInstance("TLS").apply {
            init(null, tmf.trustManagers, null)
        }
    }

    private fun parseHostPort(addr: String): Pair<String, Int> {
        val lastColon = addr.lastIndexOf(':')
        val host = addr.substring(0, lastColon)
        val port = addr.substring(lastColon + 1).toInt()
        return host to port
    }

    private fun parseAddr(addr: String): InetSocketAddress {
        val (host, port) = parseHostPort(addr)
        return InetSocketAddress(host, port)
    }
}

private fun readToken(input: InputStream): String {
    val buf = StringBuilder()
    val b = ByteArray(1)
    while (buf.length < 4096) {
        val n = input.read(b)
        if (n < 0) throw EOFException("eof reading token")
        if (b[0] == '\n'.code.toByte()) return buf.toString()
        buf.append(b[0].toInt().toChar())
    }
    throw IOException("token too long")
}

private fun writeToken(output: OutputStream, s: String) {
    output.write("$s\n".toByteArray())
    output.flush()
}
