package me.i996.client.ui

import android.content.*
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import me.i996.client.R
import me.i996.client.service.TunnelService
import me.i996.client.util.LogBuffer
import me.i996.client.util.Prefs

class MainActivity : AppCompatActivity() {

    private lateinit var etToken: EditText
    private lateinit var etServer: EditText
    private lateinit var btnConnect: Button
    private lateinit var btnDisconnect: Button
    private lateinit var btnClearLog: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var tvAuthInfo: TextView

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                TunnelService.BROADCAST_LOG -> {
                    val msg = intent.getStringExtra(TunnelService.EXTRA_LOG_MSG) ?: return
                    appendLog(msg)
                }
                TunnelService.BROADCAST_STATUS -> {
                    val status = intent.getStringExtra(TunnelService.EXTRA_STATUS)
                    setConnected(status == TunnelService.STATUS_CONNECTED)
                }
                TunnelService.BROADCAST_AUTH_RESULT -> {
                    val ok = intent.getBooleanExtra(TunnelService.EXTRA_AUTH_OK, false)
                    val msg = intent.getStringExtra(TunnelService.EXTRA_AUTH_MSG) ?: ""
                    if (ok) {
                        val webUrl = intent.getStringExtra("map_web_url") ?: ""
                        val tcpUrl = intent.getStringExtra("map_tcp_url") ?: ""
                        val openId = intent.getStringExtra("map_open_id") ?: ""
                        val cname = intent.getStringExtra("map_cname") ?: ""
                        val priv = intent.getStringExtra("map_private") ?: ""
                        val mult = intent.getStringExtra("map_mult_tunnel") ?: ""
                        showAuthInfo(openId, webUrl, tcpUrl, cname, priv, mult)
                    } else {
                        tvAuthInfo.text = "❌ 认证失败: $msg"
                        tvAuthInfo.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        setupListeners()
        loadSavedPrefs()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(TunnelService.BROADCAST_LOG)
            addAction(TunnelService.BROADCAST_STATUS)
            addAction(TunnelService.BROADCAST_AUTH_RESULT)
        }
        // 兼容所有 API 版本
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
        // 刷新历史日志
        reloadLogs()
    }

    override fun onPause() {
        super.onPause()
        runCatching { unregisterReceiver(receiver) }
    }

    private fun bindViews() {
        etToken = findViewById(R.id.et_token)
        etServer = findViewById(R.id.et_server)
        btnConnect = findViewById(R.id.btn_connect)
        btnDisconnect = findViewById(R.id.btn_disconnect)
        btnClearLog = findViewById(R.id.btn_clear_log)
        tvStatus = findViewById(R.id.tv_status)
        tvLog = findViewById(R.id.tv_log)
        scrollView = findViewById(R.id.scroll_log)
        tvAuthInfo = findViewById(R.id.tv_auth_info)
    }

    private fun setupListeners() {
        btnConnect.setOnClickListener {
            val token = etToken.text.toString().trim()
            val server = etServer.text.toString().trim()
            if (token.isEmpty()) {
                Toast.makeText(this, "请输入 Token", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Prefs.saveToken(this, token)
            Prefs.saveServer(this, server)
            tvAuthInfo.visibility = View.GONE
            LogBuffer.clear()
            tvLog.text = ""
            startTunnel(token, server)
        }

        btnDisconnect.setOnClickListener {
            stopTunnel()
        }

        btnClearLog.setOnClickListener {
            LogBuffer.clear()
            tvLog.text = ""
        }
    }

    private fun loadSavedPrefs() {
        etToken.setText(Prefs.getToken(this))
        etServer.setText(Prefs.getServer(this))
        // 启动时默认显示未连接，不自动连接
        setConnected(false)
    }

    private fun reloadLogs() {
        val all = LogBuffer.getAll()
        if (all.isNotEmpty()) {
            tvLog.text = all.joinToString("\n")
            scrollToBottom()
        }
    }

    private fun startTunnel(token: String, server: String) {
        val intent = TunnelService.startIntent(this, token, server)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        tvStatus.text = "连接中..."
        tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_dark))
        btnConnect.isEnabled = false
        btnDisconnect.isEnabled = true
    }

    private fun stopTunnel() {
        // 必须用 startService 发 ACTION_STOP，不能用 stopService
        startService(TunnelService.stopIntent(this))
        setConnected(false)
        tvAuthInfo.visibility = View.GONE
    }

    private fun setConnected(connected: Boolean) {
        runOnUiThread {
            btnConnect.isEnabled = !connected
            btnDisconnect.isEnabled = connected
            if (connected) {
                tvStatus.text = "● 已连接"
                tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
            } else {
                tvStatus.text = "○ 未连接"
                tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))
                btnConnect.isEnabled = true
                btnDisconnect.isEnabled = false
            }
        }
    }

    private fun appendLog(msg: String) {
        runOnUiThread {
            val current = tvLog.text.toString()
            val lines = if (current.isEmpty()) mutableListOf() else current.split("\n").toMutableList()
            lines.add(msg)
            if (lines.size > 200) lines.subList(0, lines.size - 200).clear()
            tvLog.text = lines.joinToString("\n")
            scrollToBottom()
        }
    }

    private fun scrollToBottom() {
        scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun showAuthInfo(
        openId: String, webUrl: String, tcpUrl: String,
        cname: String, priv: String, mult: String
    ) {
        runOnUiThread {
            val sb = StringBuilder()
            sb.appendLine("✅ 连接成功！")
            if (openId.isNotEmpty()) sb.appendLine("OpenId: $openId")
            if (webUrl.isNotEmpty()) sb.appendLine("Web地址: $webUrl")
            if (tcpUrl.isNotEmpty()) sb.appendLine("TCP地址: $tcpUrl")
            if (cname.isNotEmpty()) sb.appendLine("CNAME: $cname")
            if (priv.isNotEmpty()) sb.appendLine("内网地址: $priv")
            if (mult.isNotEmpty() && mult != "未配置") sb.appendLine("多隧道: $mult")
            tvAuthInfo.text = sb.toString().trim()
            tvAuthInfo.visibility = View.VISIBLE
            setConnected(true)
        }
    }
}