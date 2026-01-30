package com.i996.nat

import android.app.ActivityManager
import android.content.*
import android.os.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import kotlinx.coroutines.Job

class MainActivity : AppCompatActivity() {
    private lateinit var btnToggle: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvLogs: TextView
    private lateinit var svLogs: ScrollView
    private lateinit var btnClearLogs: Button

    private var isServiceRunning = false
    private val logReceiver = LogBroadcastReceiver()
    private var logCheckJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        registerLogReceiver()
        checkServiceStatus()
        startLogPolling()
    }

    private fun initViews() {
        btnToggle = findViewById(R.id.btnToggle)
        tvStatus = findViewById(R.id.tvStatus)
        tvLogs = findViewById(R.id.tvLogs)
        svLogs = findViewById(R.id.svLogs)
        btnClearLogs = findViewById(R.id.btnClearLogs)

        btnToggle.setOnClickListener {
            if (isServiceRunning) {
                stopNATService()
            } else {
                startNATService()
            }
        }

        btnClearLogs.setOnClickListener {
            tvLogs.text = ""
        }
    }

    private fun startNATService() {
        val intent = Intent(this, NATService::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        addLog("正在启动 i996 内网穿透服务...")
        addLog("Token: tian")
        addLog("服务器: i996.me:8223")

        // 延迟检查服务状态，等待服务真正启动
        lifecycleScope.launch {
            delay(500)
            checkServiceStatus()
        }
    }

    private fun stopNATService() {
        val intent = Intent(this, NATService::class.java)
        stopService(intent)
        addLog("正在停止服务...")

        // 延迟检查服务状态，等待服务真正停止
        lifecycleScope.launch {
            delay(500)
            checkServiceStatus()
        }
    }

    private fun updateUI(running: Boolean) {
        isServiceRunning = running
        btnToggle.text = if (running) "停止" else "启动"
        tvStatus.text = if (running) "运行中" else "已停止"
        tvStatus.setTextColor(
            if (running) getColor(android.R.color.holo_green_dark)
            else getColor(android.R.color.holo_red_dark)
        )
    }

    private fun checkServiceStatus() {
        // 使用 ActivityManager 检查服务是否真的在运行
        val manager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val running = manager.getRunningServices(Integer.MAX_VALUE)
            .any { it.service.className == NATService::class.java.name }
        updateUI(running)
    }

    private fun registerLogReceiver() {
        val filter = IntentFilter("com.i996.nat.LOG")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(logReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(logReceiver, filter)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(logReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        logCheckJob?.cancel()
    }

    override fun onResume() {
        super.onResume()
        // 从后台返回时刷新服务状态
        checkServiceStatus()
    }

    private fun startLogPolling() {
        logCheckJob = lifecycleScope.launch {
            var lastLogCount = 0
            while (isActive) {
                delay(500) // 每500毫秒检查一次

                val prefs = getSharedPreferences("i996_logs", MODE_PRIVATE)
                val logs = prefs.getStringSet("logs", null)?.toList()?.sorted() ?: emptyList()

                // 如果有新日志，显示它们（倒序显示，最新的在前）
                if (logs.size != lastLogCount) {
                    val newLogs = logs.drop(lastLogCount).reversed()
                    newLogs.forEach { logEntry ->
                        val parts = logEntry.split(":", limit = 2)
                        if (parts.size == 2) {
                            val message = parts[1]
                            addLogToUI(message)
                        }
                    }
                    lastLogCount = logs.size
                }
            }
        }
    }

    private fun addLog(message: String) {
        addLogToUI(message)
    }

    private fun addLogToUI(message: String) {
        runOnUiThread {
            val time = android.text.format.DateFormat.format("yy/MM/dd HH:mm:ss", System.currentTimeMillis())
            val currentText = tvLogs.text.toString()
            tvLogs.text = "[$time] $message\n$currentText"
            svLogs.post { svLogs.fullScroll(ScrollView.FOCUS_UP) }
        }
    }

    inner class LogBroadcastReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val log = intent?.getStringExtra("log") ?: return
            android.util.Log.d("MainActivity", "收到日志: $log")
            addLog(log)
        }
    }
}
