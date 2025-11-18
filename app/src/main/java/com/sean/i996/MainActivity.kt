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
import java.io.BufferedReader
import java.io.InputStreamReader

// 导入 Go 导出的包
// Gomobile 导出的包名通常是 Go 模块名 (mobileclient)
import mobileclient.Mobileclient // 用于访问 NewClient 等包级函数
import mobileclient.Client // Client 结构体对应的类

class MainActivity : AppCompatActivity() {

    private lateinit var logTextView: TextView
    private lateinit var scrollView: ScrollView
    private var tunnelClient: Client? = null
    // 启用状态报告：由于 Go 代码中已添加 statusCh，我们保留状态 Job
    private var statusJob: Job? = null

    // TODO: 配置区域 - 替换为你的实际信息
    private val serverIp = "192.168.1.130"
    private val serverPort = 3333
    private val clientId = "testid"
    private val serverAddr = "$serverIp:$serverPort"
    private val certFileName = "cert.pem" // 证书文件在 assets 目录下的名称

    // 使用主线程 CoroutineScope 来管理协程
    private val mainScope = CoroutineScope(Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // UI 初始化代码
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        val btnStart = Button(this).apply {
            text = "Start Go Tunnel"
            setOnClickListener { startTunnel() }
        }

        val btnStop = Button(this).apply {
            text = "Stop Go Tunnel"
            setOnClickListener { stopTunnel() }
        }

        scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0, 1f
            )
        }

        logTextView = TextView(this).apply {
            text = "Go Mobile Tunnel Ready.\nServer: $serverAddr\nClient ID: $clientId\n"
            textSize = 12f
        }

        scrollView.addView(logTextView)
        layout.addView(btnStart)
        layout.addView(btnStop)
        layout.addView(scrollView)

        setContentView(layout)

        // 移除导致错误的 Mobileclient.init() 调用。
        // 因为您的 Go 库不需要全局初始化，所以不需要这个调用。
    }

    private fun log(msg: String) {
        Log.d("GoTunnel", msg)
        runOnUiThread {
            logTextView.append("${java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date())} $msg\n")
            scrollView.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    // 从 assets 目录加载证书内容
    private fun loadCertFromAssets(): String? {
        return try {
            assets.open(certFileName).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            log("Error loading $certFileName: ${e.message}")
            null
        }
    }

    private fun startTunnel() = mainScope.launch {
        if (tunnelClient != null) {
            log("Tunnel already running.")
            return@launch
        }

        logTextView.text = ""
        log("Loading certificate...")

        // 在 IO 线程加载证书
        val certPEM = withContext(Dispatchers.IO) { loadCertFromAssets() }

        if (certPEM.isNullOrEmpty()) {
            log("Failed to load certificate. Aborting start.")
            return@launch
        }

        log("Creating Go Client for $serverAddr...")

        try {
            // FIX 1: NewClient 是 Go 包级别函数，在 Kotlin 中必须通过 Mobileclient 类调用。
            tunnelClient = Mobileclient.NewClient(serverAddr, clientId, certPEM)

            // 启动 Go 端的连接循环 (Start 方法已在 Go 代码中正确导出)
            tunnelClient?.Start()
            log("Go Client started successfully.")

            // 启动协程接收 Go 端的实时状态（Go 代码中已添加 GetStatusChannel）
            statusJob = launch {
                // FIX 2: Go 导出的方法必须是大写开头，即 GetStatusChannel
                val statusChannel = tunnelClient!!.GetStatusChannel()
                while (statusChannel.next()) {
                    val statusMsg = statusChannel.get()
                    log("Go Status: $statusMsg")
                }
                log("Go Status Channel closed.")
            }


        } catch (e: Exception) {
            log("Failed to initialize Go Client: ${e.message}")
            tunnelClient = null
        }
    }

    private fun stopTunnel() {
        if (tunnelClient == null) {
            log("Tunnel is not running.")
            return
        }

        // 调用 Go 端的 Stop 方法停止连接 (Stop 方法已在 Go 代码中正确导出)
        tunnelClient?.Stop()
        statusJob?.cancel()
        tunnelClient = null
        log("Tunnel stop initiated.")
    }

    override fun onDestroy() {
        super.onDestroy()
        stopTunnel()
        mainScope.cancel() // 取消所有主协程
    }
}