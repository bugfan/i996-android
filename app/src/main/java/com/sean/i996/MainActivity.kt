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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadTheme()
        setContentView(R.layout.activity_main)

        initViews()
        registerLogReceiver()
        checkServiceStatus()
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
        addLog("Token: tian")
        addLog("服务器: i996.me:8223")

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
    }

    override fun onResume() {
        super.onResume()
        // 从后台返回时刷新服务状态
        checkServiceStatus()
        invalidateOptionsMenu()
    }

    private fun parseAndExtractTunnelInfo(message: String) {
        // 解析Go日志中的关键信息
        when {
            message.contains("您的OpenId为") -> {
                val openId = extractValue(message, "=>")
                tunnelInfo.append("OpenId: $openId\n")
            }
            message.contains("您的Web访问地址为") -> {
                val url = extractValue(message, "=>")
                tunnelInfo.append("Web: $url\n")
            }
            message.contains("您的TCP访问地址为") -> {
                val tcp = extractValue(message, "=>")
                tunnelInfo.append("TCP: $tcp\n")
            }
            message.contains("您的CNAME地址为") -> {
                val cname = extractValue(message, "=>")
                tunnelInfo.append("CNAME: $cname\n")
            }
            message.contains("您的内网地址为") -> {
                val localAddr = extractValue(message, "=>")
                tunnelInfo.append("内网: $localAddr\n")
            }
            message.matches(Regex("\\[\\d+\\].*->.*")) -> {
                // 多隧道配置，例如: [1] test-fuck.i996.me -> http://192.168.1.2
                tunnelInfo.append("$message\n")
            }
            message.contains("您的多隧道配置为") -> {
                tunnelInfo.append("多隧道:\n")
            }
        }
        // BroadcastReceiver 已经在主线程，不需要 runOnUiThread
        tvTunnelInfo.text = tunnelInfo.toString()
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

        val time = android.text.format.DateFormat.format("yy/MM/dd HH:mm:ss", System.currentTimeMillis())
        val newLog = "[$time] $message\n"
        tvLogs.append(newLog)
        logLineCount++

        // 滚动到顶部
        svLogs.post { svLogs.fullScroll(ScrollView.FOCUS_UP) }
    }

    inner class LogBroadcastReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val log = intent?.getStringExtra("log") ?: return
            android.util.Log.d("MainActivity", "收到日志: $log")
            addLog(log)
            parseAndExtractTunnelInfo(log)
        }
    }
}
