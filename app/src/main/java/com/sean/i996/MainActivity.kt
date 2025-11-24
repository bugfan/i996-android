package com.i996.nat

import android.content.*
import android.os.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var etToken: EditText
    private lateinit var etPrivateHost: EditText
    private lateinit var btnToggle: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvLogs: TextView
    private lateinit var svLogs: ScrollView
    private lateinit var btnClearLogs: Button

    private var isServiceRunning = false
    private val logReceiver = LogBroadcastReceiver()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        loadConfig()
        registerLogReceiver()
        checkServiceStatus()
    }

    private fun initViews() {
        etToken = findViewById(R.id.etToken)
        etPrivateHost = findViewById(R.id.etPrivateHost)
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

    private fun loadConfig() {
        val prefs = getSharedPreferences("nat_config", MODE_PRIVATE)
        etToken.setText(prefs.getString("token", ""))
        etPrivateHost.setText(prefs.getString("private_host", "127.0.0.1:8080"))
    }

    private fun saveConfig() {
        val prefs = getSharedPreferences("nat_config", MODE_PRIVATE)
        prefs.edit().apply {
            putString("token", etToken.text.toString())
            putString("private_host", etPrivateHost.text.toString())
            apply()
        }
    }

    private fun startNATService() {
        val token = etToken.text.toString().trim()
        val privateHost = etPrivateHost.text.toString().trim()

        if (token.isEmpty()) {
            Toast.makeText(this, "请输入Token", Toast.LENGTH_SHORT).show()
            return
        }

        if (privateHost.isEmpty()) {
            Toast.makeText(this, "请输入内网地址", Toast.LENGTH_SHORT).show()
            return
        }

        saveConfig()

        val intent = Intent(this, NATService::class.java).apply {
            putExtra("token", token)
            putExtra("private_host", privateHost)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        updateUI(true)
        addLog("正在启动内网穿透服务...")
    }

    private fun stopNATService() {
        val intent = Intent(this, NATService::class.java)
        stopService(intent)
        updateUI(false)
        addLog("服务已停止")
    }

    private fun updateUI(running: Boolean) {
        isServiceRunning = running
        btnToggle.text = if (running) "停止" else "启动"
        tvStatus.text = if (running) "运行中" else "已停止"
        tvStatus.setTextColor(
            if (running) getColor(android.R.color.holo_green_dark)
            else getColor(android.R.color.holo_red_dark)
        )
        etToken.isEnabled = !running
        etPrivateHost.isEnabled = !running
    }

    private fun checkServiceStatus() {
        val prefs = getSharedPreferences("nat_config", MODE_PRIVATE)
        val running = prefs.getBoolean("service_running", false)
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

    private fun addLog(message: String) {
        runOnUiThread {
            val time = android.text.format.DateFormat.format("yyyy-MM-dd HH:mm:ss", System.currentTimeMillis())
            tvLogs.append("[$time] $message\n")
            svLogs.post { svLogs.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    inner class LogBroadcastReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val log = intent?.getStringExtra("log") ?: return
            addLog(log)
        }
    }
}