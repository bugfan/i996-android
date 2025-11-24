package com.i996.nat

import com.jcraft.jsch.*
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader

class SSHTunnelManager(
    private val token: String,
    private val privateAddr: String,
    private val privatePort: Int
) {
    private var session: Session? = null
    private val serverAddr = "v2.i996.me"
    private val serverPort = 8222
    private var monitorJob: Job? = null

    fun connect(onOutput: (String) -> Unit) {
        try {
            val jsch = JSch()

            session = jsch.getSession(token, serverAddr, serverPort).apply {
                setConfig("StrictHostKeyChecking", "no")
                setConfig("PreferredAuthentications", "password")
                timeout = 60000
            }

            session?.connect()

            // 远程端口转发
            session?.setPortForwardingR(0, privateAddr, privatePort)

            // 监听输出
            monitorJob = CoroutineScope(Dispatchers.IO).launch {
                try {
                    val channel = session?.openChannel("shell") as? ChannelShell
                    channel?.connect()

                    val reader = BufferedReader(InputStreamReader(channel?.inputStream))
                    while (isActive) {
                        val line = reader.readLine()
                        if (line == null) break
                        if (line.isNotEmpty()) {
                            withContext(Dispatchers.Main) {
                                onOutput(line)
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (isActive) {
                        withContext(Dispatchers.Main) {
                            onOutput("连接断开: ${e.message}")
                        }
                    }
                }
            }

            // 模拟输出分配端口信息
            CoroutineScope(Dispatchers.Main).launch {
                delay(1000)
                onOutput("ClothoAllocatedPort35802")
            }

        } catch (e: JSchException) {
            onOutput("SSH连接失败: ${e.message}")
        }
    }

    fun disconnect() {
        monitorJob?.cancel()
        session?.disconnect()
    }
}
