package com.sean.i996

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

class MainActivity : AppCompatActivity() {
    private var tunnelService: TunnelService? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as TunnelService.TunnelBinder
            tunnelService = binder.getService()
            bound = true
            updateStatus("Service Connected")
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            bound = false
            updateStatus("Service Disconnected")
        }
    }

    private lateinit var serverAddrInput: EditText
    private lateinit var clientIdInput: EditText
    private lateinit var certPemInput: EditText
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        requestPermissions()
    }

    private fun initViews() {
        serverAddrInput = findViewById(R.id.serverAddrInput)
        clientIdInput = findViewById(R.id.clientIdInput)
        certPemInput = findViewById(R.id.certPemInput)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        statusText = findViewById(R.id.statusText)

        // 设置默认值
        serverAddrInput.setText("192.168.1.130:3333")
        clientIdInput.setText("testid")

        startButton.setOnClickListener {
            startTunnelService()
        }

        stopButton.setOnClickListener {
            stopTunnelService()
        }
    }

    private fun requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                1
            )
        }
    }

    private fun startTunnelService() {
        val serverAddr = serverAddrInput.text.toString()
        val clientId = clientIdInput.text.toString()
        val certPem = certPemInput.text.toString()

        if (serverAddr.isEmpty() || clientId.isEmpty()) {
            updateStatus("Please fill in server address and client ID")
            return
        }

        val intent = Intent(this, TunnelService::class.java).apply {
            putExtra("serverAddr", serverAddr)
            putExtra("clientId", clientId)
            if (certPem.isNotEmpty()) {
                putExtra("certPem", certPem)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        bindService(intent, connection, Context.BIND_AUTO_CREATE)
        updateStatus("Starting tunnel service...")
    }

    private fun stopTunnelService() {
        if (bound) {
            unbindService(connection)
            bound = false
        }
        stopService(Intent(this, TunnelService::class.java))
        updateStatus("Tunnel service stopped")
    }

    private fun updateStatus(message: String) {
        runOnUiThread {
            statusText.text = "Status: $message"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (bound) {
            unbindService(connection)
            bound = false
        }
    }
}