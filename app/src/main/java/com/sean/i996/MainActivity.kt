package com.i996.nat

import android.app.ActivityManager
import android.content.*
import android.os.*
import android.view.Menu
import android.view.MenuItem
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import kotlinx.coroutines.Job

class MainActivity : AppCompatActivity() {
    private lateinit var btnToggle: Button
    private lateinit var tvLogs: TextView
    private lateinit var svLogs: ScrollView
    private lateinit var btnClearLogs: Button
    private lateinit var tvTunnelInfo: TextView

    private var isServiceRunning = false
    private var isDarkMode = false
    private val logReceiver = LogBroadcastReceiver()
    private val tunnelInfo = StringBuilder()

    // 限制日志行数，避免内存溢出
    private var logLineCount = 0
    private val MAX_LOG_LINES = 500

    // 日志轮询 Handler
    private val logPollHandler = Handler(Looper.getMainLooper())
    private val logPollRunnable = object : Runnable {
        override fun run() {
            checkLogCache()
            logPollHandler.postDelayed(this, 500) // 每 500ms 检查一次
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadTheme()
        setContentView(R.layout.activity_main)

        initViews()
        registerLogReceiver()
        checkServiceStatus()

        // 启动日志轮询
        startLogPolling()
    }

    private fun startLogPolling() {
        logPollHandler.post(logPollRunnable)
    }

    private fun stopLogPolling() {
        logPollHandler.removeCallbacks(logPollRunnable)
    }

    private fun checkLogCache() {
        try {
            val logs = LogCache.getAndClearLogs(this)
            if (logs.isNotEmpty()) {
                android.util.Log.d("MainActivity", "从 LogCache 读取到 ${logs.size} 条日志")
                for (log in logs) {
                    // 先解析并提取隧道信息到上方框（不返回值，不影响日志显示）
                    parseAndExtractTunnelInfo(log)
                    // 所有日志都添加到日志区域（包括隧道配置信息）
                    addLog(log)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "读取 LogCache 失败: ${e.message}")
        }
    }

    private fun loadTheme() {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        isDarkMode = prefs.getBoolean("dark_mode", false)
        val nightMode = if (isDarkMode) {
            AppCompatDelegate.MODE_NIGHT_YES
        } else {
            AppCompatDelegate.MODE_NIGHT_NO
        }
        AppCompatDelegate.setDefaultNightMode(nightMode)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        updateThemeIcon(menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_theme_toggle -> {
                toggleTheme()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun updateThemeIcon(menu: Menu?) {
        menu?.findItem(R.id.action_theme_toggle)?.setIcon(
            if (isDarkMode) R.drawable.ic_sun else R.drawable.ic_moon
        )
    }

    private fun initViews() {
        btnToggle = findViewById(R.id.btnToggle)
        tvLogs = findViewById(R.id.tvLogs)
        svLogs = findViewById(R.id.svLogs)
        btnClearLogs = findViewById(R.id.btnClearLogs)
        tvTunnelInfo = findViewById(R.id.tvTunnelInfo)

        btnToggle.setOnClickListener {
            if (isServiceRunning) {
                stopNATService()
            } else {
                startNATService()
            }
        }

        btnClearLogs.setOnClickListener {
            tvLogs.text = ""
            logLineCount = 0
        }
    }

    private fun toggleTheme() {
        isDarkMode = !isDarkMode

        // 保存主题设置
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        prefs.edit().putBoolean("dark_mode", isDarkMode).apply()

        // 直接切换主题，不重新创建Activity
        val nightMode = if (isDarkMode) {
            AppCompatDelegate.MODE_NIGHT_YES
        } else {
            AppCompatDelegate.MODE_NIGHT_NO
        }
        AppCompatDelegate.setDefaultNightMode(nightMode)

        // 更新菜单图标
        invalidateOptionsMenu()
    }

    private fun startNATService() {
        val intent = Intent(this, NATService::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        addLog("正在启动 i996 内网穿透服务...")

        // 清空隧道信息
        tunnelInfo.clear()
        tvTunnelInfo.text = "暂无隧道信息"

        // 延迟检查服务状态，等待服务真正启动
        lifecycleScope.launch {
            delay(500)
            checkServiceStatus()
        }
    }

    private fun stopNATService() {
        val intent = Intent(this, NATService::class.java)
        stopService(intent)

        // 清空隧道信息
        tunnelInfo.clear()
        tvTunnelInfo.text = "暂无隧道信息"
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

        // 更新按钮背景：使用俏皮的颜色和圆角
        val bgRes = if (running) {
            R.drawable.btn_running_bg
        } else {
            R.drawable.btn_stopped_bg
        }
        btnToggle.setBackgroundResource(bgRes)
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
        android.util.Log.d("MainActivity", "注册广播接收器")
        // 对于同一应用内的广播，不使用 RECEIVER_NOT_EXPORTED
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // 导出 = false，但可以接收同一应用的广播
            registerReceiver(logReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(logReceiver, filter)
        }
        android.util.Log.d("MainActivity", "广播接收器已注册")
    }

    override fun onDestroy() {
        super.onDestroy()
        stopLogPolling()
        try {
            unregisterReceiver(logReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onResume() {
        super.onResume()
        // 从后台返回时刷新服务状态
        checkServiceStatus()
        invalidateOptionsMenu()
        // 确保 logcat 监听在运行
        if (!isPolling()) {
            startLogPolling()
        }
    }

    override fun onPause() {
        super.onPause()
        // 在暂停时停止轮询以节省资源
        stopLogPolling()
    }

    private fun isPolling(): Boolean {
        // 简单的检查方式
        return true // 实际上可以添加一个标志位
    }

    private fun parseAndExtractTunnelInfo(message: String) {
        android.util.Log.d("MainActivity", "解析隧道信息: $message")

        // 先过滤掉不需要的提示性文字
        val filteredMessage = message
            .replace("您的多隧道配置为 => 👇", "")
            .replace("您的多隧道配置为=>👇", "")
            .replace("您的多隧道配置为 =>", "")
            .replace("您的多隧道配置为=>", "")
            .replace("您的OpenId为 => ", "OpenId: ")
            .replace("您的Web访问地址为 => ", "Web: ")
            .replace("您的TCP访问地址为 => ", "TCP: ")
            .replace("您的CNAME地址为 => ", "CNAME: ")
            .replace("您的内网地址为 => ", "内网: ")
            .replace("http(s)://", "https://")
            .trim()

        if (filteredMessage.isEmpty()) return

        // 解析Go日志中的关键信息
        when {
            filteredMessage.contains("OpenId: ") && !filteredMessage.contains("[") -> {
                // OpenId 单行信息
                val openId = extractValue(filteredMessage, "OpenId: ")
                tunnelInfo.append("OpenId: $openId\n")
                tvTunnelInfo.text = tunnelInfo.toString()
            }
            filteredMessage.contains("Web: ") && !filteredMessage.contains("[") -> {
                // Web 单行信息
                val url = extractValue(filteredMessage, "Web: ")
                tunnelInfo.append("Web: $url\n")
                tvTunnelInfo.text = tunnelInfo.toString()
            }
            filteredMessage.contains("TCP: ") && !filteredMessage.contains("[") -> {
                // TCP 单行信息
                val tcp = extractValue(filteredMessage, "TCP: ")
                tunnelInfo.append("TCP: $tcp\n")
                tvTunnelInfo.text = tunnelInfo.toString()
            }
            filteredMessage.contains("CNAME: ") && !filteredMessage.contains("[") -> {
                // CNAME 单行信息
                val cname = extractValue(filteredMessage, "CNAME: ")
                tunnelInfo.append("CNAME: $cname\n")
                tvTunnelInfo.text = tunnelInfo.toString()
            }
            filteredMessage.contains("内网: ") && !filteredMessage.contains("[") -> {
                // 内网地址单行信息
                val localAddr = extractValue(filteredMessage, "内网: ")
                tunnelInfo.append("内网: $localAddr\n")
                tvTunnelInfo.text = tunnelInfo.toString()
            }
            filteredMessage.matches(Regex("\\[\\d+\\].*->.*")) -> {
                // 多隧道配置，例如: [1] test-fuck.i996.me -> http://192.168.1.2
                tunnelInfo.append("$filteredMessage\n")
                tvTunnelInfo.text = tunnelInfo.toString()
            }
        }
    }

    private fun extractValue(message: String, separator: String): String {
        return message.substringAfter(separator).trim()
    }

    private fun addLog(message: String) {
        // 限制日志行数
        if (logLineCount >= MAX_LOG_LINES) {
            // 移除最旧的一行（从末尾开始找第一个换行符）
            val currentText = tvLogs.text.toString()
            val lastNewlineIndex = currentText.lastIndexOf('\n')
            if (lastNewlineIndex > 0) {
                tvLogs.text = currentText.substring(0, lastNewlineIndex)
                logLineCount--
            }
        }

        // 更紧凑的时间格式：只显示时分秒
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val newLog = "[$time] $message\n"

        // 新日志添加到顶部，而不是底部
        tvLogs.text = newLog + tvLogs.text
        logLineCount++

        // 滚动到顶部
        svLogs.post { svLogs.fullScroll(ScrollView.FOCUS_UP) }
    }

    inner class LogBroadcastReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val log = intent?.getStringExtra("log") ?: return
            android.util.Log.d("MainActivity", "收到广播日志: [$log]")
            addLog(log)
            parseAndExtractTunnelInfo(log)
        }
    }
}
