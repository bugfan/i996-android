package com.sean.i996

import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

// 导入 Go 生成的类
import mobileclient.Mobileclient
import mobileclient.Client

class MainActivity : AppCompatActivity() {

    private lateinit var logTextView: TextView
    private lateinit var scrollView: ScrollView
    private var tunnelClient: Client? = null
    private var statusJob: Job? = null

    // 配置信息
    private val serverIp = "192.168.1.130"  // 修改为你的服务器 IP
    private val serverPort = 3333
    private val clientId = "testid"
    private val serverAddr = "$serverIp:$serverPort"
    private val certFileName = "cert.pem" // 证书文件放在 assets 目录

    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 创建 UI
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        val btnStart = Button(this).apply {
            text = "启动隧道"
            setOnClickListener { startTunnel() }
        }

        val btnStop = Button(this).apply {
            text = "停止隧道"
            setOnClickListener { stopTunnel() }
        }

        val btnStatus = Button(this).apply {
            text = "检查状态"
            setOnClickListener { checkStatus() }
        }

        scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0, 1f
            )
        }

        logTextView = TextView(this).apply {
            text = """
                Go Tunnel Client Ready
                Server: $serverAddr
                Client ID: $clientId
                
                点击"启动隧道"开始连接
                
            """.trimIndent()
            textSize = 12f
            setPadding(16, 16, 16, 16)
        }

        scrollView.addView(logTextView)
        layout.addView(btnStart)
        layout.addView(btnStop)
        layout.addView(btnStatus)
        layout.addView(scrollView)

        setContentView(layout)
    }

    private fun log(msg: String) {
        Log.d("GoTunnel", msg)
        runOnUiThread {
            val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            logTextView.append("$timestamp $msg\n")
            scrollView.post {
                scrollView.fullScroll(ScrollView.FOCUS_DOWN)
            }
        }
    }

    private fun loadCertFromAssets(): String? {
        return try {
            val inputStream = assets.open(certFileName)
            val size = inputStream.available()
            val buffer = ByteArray(size)
            inputStream.read(buffer)
            inputStream.close()
            String(buffer, Charsets.UTF_8)
        } catch (e: Exception) {
            log("❌ 加载证书失败: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    private fun startTunnel() {
        mainScope.launch {
            try {
                if (tunnelClient != null) {
                    log("⚠️ 隧道已在运行中")
                    return@launch
                }

                log("📄 正在加载证书...")
                
                // 在 IO 线程加载证书
                val certPEM = withContext(Dispatchers.IO) {
                    loadCertFromAssets()
                }

                if (certPEM.isNullOrEmpty()) {
                    log("❌ 证书加载失败，无法启动")
                    return@launch
                }

                log("✅ 证书加载成功 (${certPEM.length} 字节)")
                log("🔌 正在创建 Go 客户端...")

                // 调用 Go 代码创建客户端
                withContext(Dispatchers.IO) {
                    try {
                        val client = Mobileclient.newClient(serverAddr, clientId, certPEM)
                        tunnelClient = client
                        log("✅ Go 客户端创建成功")
                        
                        // 启动客户端
                        client?.start()
                        log("🚀 隧道客户端已启动")
                    } catch (e: Exception) {
                        log("❌ 创建客户端失败: ${e.message}")
                        e.printStackTrace()
                        tunnelClient = null
                        return@withContext
                    }
                }

                // 启动状态监听
                statusJob = mainScope.launch(Dispatchers.IO) {
                    try {
                        while (isActive && tunnelClient != null) {
                            val status = tunnelClient?.getStatus() ?: break
                            if (status.isEmpty()) {
                                log("ℹ️ 状态通道已关闭")
                                break
                            }
                            log("📡 $status")
                        }
                    } catch (e: Exception) {
                        log("⚠️ 状态监听错误: ${e.message}")
                    }
                }

            } catch (e: Exception) {
                log("❌ 启动隧道失败: ${e.message}")
                e.printStackTrace()
                tunnelClient = null
            }
        }
    }

    private fun stopTunnel() {
        mainScope.launch {
            if (tunnelClient == null) {
                log("⚠️ 隧道未运行")
                return@launch
            }

            try {
                log("🛑 正在停止隧道...")
                
                withContext(Dispatchers.IO) {
                    tunnelClient?.stop()
                }
                
                statusJob?.cancel()
                tunnelClient = null
                
                log("✅ 隧道已停止")
            } catch (e: Exception) {
                log("❌ 停止隧道时出错: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    private fun checkStatus() {
        if (tunnelClient == null) {
            log("⚠️ 客户端未创建")
            return
        }

        mainScope.launch(Dispatchers.IO) {
            try {
                val isRunning = tunnelClient?.isRunning() ?: false
                val serverAddr = tunnelClient?.serverAddr ?: "N/A"
                val clientId = tunnelClient?.clientID ?: "N/A"
                
                withContext(Dispatchers.Main) {
                    log("📊 状态检查:")
                    log("   运行中: ${if (isRunning) "是" else "否"}")
                    log("   服务器: $serverAddr")
                    log("   客户端ID: $clientId")
                }
            } catch (e: Exception) {
                log("❌ 状态检查失败: ${e.message}")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopTunnel()
        mainScope.cancel()
        log("🔚 Activity 销毁")
    }
}