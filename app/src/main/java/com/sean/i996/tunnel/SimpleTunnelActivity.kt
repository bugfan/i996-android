package com.sean.i996.tunnel

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.sean.i996.R

class SimpleTunnelActivity : AppCompatActivity(), TunnelClient.TunnelConnectionListener {

    private lateinit var serverAddressEditText: EditText
    private lateinit var clientIdEditText: EditText
    private lateinit var connectButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var statusTextView: TextView

    private var tunnelClient: TunnelClient? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tunnel)

        initViews()
        setupClickListeners()
    }

    private fun initViews() {
        serverAddressEditText = findViewById(R.id.editTextServerAddress)
        clientIdEditText = findViewById(R.id.editTextClientId)
        connectButton = findViewById(R.id.buttonConnect)
        disconnectButton = findViewById(R.id.buttonDisconnect)
        statusTextView = findViewById(R.id.textViewStatus)

        // Set default values
        serverAddressEditText.setText("192.168.1.130:3333")  // 更新为你的服务器地址
        clientIdEditText.setText("android-tunnel-test")

        // Set initial button states
        connectButton.isEnabled = true
        disconnectButton.isEnabled = false
        statusTextView.text = "Status: Disconnected"
    }

    private fun setupClickListeners() {
        connectButton.setOnClickListener {
            val serverAddr = serverAddressEditText.text.toString().trim()
            val clientId = clientIdEditText.text.toString().trim()

            if (serverAddr.isNotEmpty() && clientId.isNotEmpty()) {
                statusTextView.text = "Connecting to $serverAddr..."
                connectButton.isEnabled = false
                disconnectButton.isEnabled = true

                // 创建并连接隧道客户端
                tunnelClient = TunnelClient(serverAddr, clientId)
                tunnelClient?.setListener(this)

                // 在后台线程连接
                Thread {
                    val success = tunnelClient?.connect() ?: false
                    runOnUiThread {
                        if (!success) {
                            connectButton.isEnabled = true
                            disconnectButton.isEnabled = false
                            statusTextView.text = "Status: Connection failed"
                        }
                    }
                }.start()

            } else {
                Toast.makeText(this, "请输入服务器地址和客户端ID", Toast.LENGTH_SHORT).show()
            }
        }

        disconnectButton.setOnClickListener {
            tunnelClient?.disconnect()
            tunnelClient = null
            connectButton.isEnabled = true
            disconnectButton.isEnabled = false
            statusTextView.text = "Status: Disconnected"
            Toast.makeText(this, "断开连接", Toast.LENGTH_SHORT).show()
        }
    }

    // TunnelClient.TunnelConnectionListener 实现
    override fun onConnected() {
        runOnUiThread {
            statusTextView.text = "Status: Connected - Tunnel active"
            Toast.makeText(this, "隧道连接成功！", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDisconnected() {
        runOnUiThread {
            statusTextView.text = "Status: Disconnected"
            connectButton.isEnabled = true
            disconnectButton.isEnabled = false
            Toast.makeText(this, "隧道连接断开", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onConnectionRequest(connectionId: Long) {
        runOnUiThread {
            statusTextView.text = "Status: Connected - Forwarding request $connectionId"
            Toast.makeText(this, "收到连接请求: $connectionId", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onError(error: String) {
        runOnUiThread {
            statusTextView.text = "Status: Error - $error"
            connectButton.isEnabled = true
            disconnectButton.isEnabled = false
            Toast.makeText(this, "连接错误: $error", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        tunnelClient?.disconnect()
    }
}