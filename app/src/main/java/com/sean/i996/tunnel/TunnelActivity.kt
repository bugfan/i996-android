package com.sean.i996.tunnel

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Observer
import com.sean.i996.R

class TunnelActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "TunnelActivity"
    }

    private lateinit var serverAddressEditText: EditText
    private lateinit var clientIdEditText: EditText
    private lateinit var connectButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var statusTextView: TextView
    private lateinit var connectionsTextView: TextView
    private lateinit var dataTransferredTextView: TextView

    private var tunnelService: TunnelProxyService? = null
    private var isServiceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as TunnelProxyService.TunnelBinder
            tunnelService = binder.getService()
            isServiceBound = true

            Log.d(TAG, "Tunnel service connected")

            // Observe service status
            observeServiceStatus()
            updateUI()
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isServiceBound = false
            tunnelService = null
            Log.d(TAG, "Tunnel service disconnected")
        }
    }

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
        connectionsTextView = findViewById(R.id.textViewConnections)
        dataTransferredTextView = findViewById(R.id.textViewDataTransferred)

        // Set default values
        serverAddressEditText.setText("127.0.0.1:3333")
        clientIdEditText.setText("android-tunnel-${System.currentTimeMillis()}")

        updateUI()
    }

    private fun setupClickListeners() {
        connectButton.setOnClickListener {
            val serverAddress = serverAddressEditText.text.toString().trim()
            val clientId = clientIdEditText.text.toString().trim()

            if (serverAddress.isEmpty()) {
                Toast.makeText(this, "Please enter server address", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (clientId.isEmpty()) {
                Toast.makeText(this, "Please enter client ID", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            startTunnelService(serverAddress, clientId)
        }

        disconnectButton.setOnClickListener {
            stopTunnelService()
        }
    }

    private fun startTunnelService(serverAddress: String, clientId: String) {
        Log.d(TAG, "Starting tunnel service with address: $serverAddress, client ID: $clientId")

        val intent = Intent(this, TunnelProxyService::class.java).apply {
            action = "START_TUNNEL"
            putExtra("SERVER_ADDRESS", serverAddress)
            putExtra("CLIENT_ID", clientId)
        }

        startService(intent)

        // Bind to service
        Intent(this, TunnelProxyService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    private fun stopTunnelService() {
        Log.d(TAG, "Stopping tunnel service")

        val intent = Intent(this, TunnelProxyService::class.java).apply {
            action = "STOP_TUNNEL"
        }

        startService(intent)

        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
    }

    private fun observeServiceStatus() {
        tunnelService?.let { service ->
            service.connectionStatus.observe(this, Observer { status ->
                status?.let {
                    Log.d(TAG, "Connection status changed: $it")
                    updateUI()
                }
            })

            service.activeConnections.observe(this, Observer { count ->
                count?.let {
                    Log.d(TAG, "Active connections changed: $it")
                    updateUI()
                }
            })

            service.totalDataTransferred.observe(this, Observer { bytes ->
                bytes?.let {
                    Log.d(TAG, "Data transferred changed: $it bytes")
                    updateUI()
                }
            })
        }
    }

    private fun updateUI() {
        val service = tunnelService
        val status = service?.getConnectionStatus()
        val connectionsCount = service?.getActiveConnectionsCount() ?: 0
        val dataTransferred = service?.getTotalDataTransferred() ?: 0L

        // Update status text
        val statusText = when (status) {
            TunnelProxyService.ConnectionStatus.DISCONNECTED -> "Disconnected"
            TunnelProxyService.ConnectionStatus.CONNECTING -> "Connecting..."
            TunnelProxyService.ConnectionStatus.CONNECTED -> "Connected"
            TunnelProxyService.ConnectionStatus.ERROR -> "Error"
            null -> "Unknown"
        }
        statusTextView.text = "Status: $statusText"

        // Update connections count
        connectionsTextView.text = "Active Connections: $connectionsCount"

        // Update data transferred
        val dataText = when {
            dataTransferred < 1024 -> "$dataTransferred B"
            dataTransferred < 1024 * 1024 -> "${dataTransferred / 1024} KB"
            dataTransferred < 1024 * 1024 * 1024 -> "${dataTransferred / (1024 * 1024)} MB"
            else -> "${dataTransferred / (1024 * 1024 * 1024)} GB"
        }
        dataTransferredTextView.text = "Data Transferred: $dataText"

        // Update button states
        val isRunning = service?.isRunning() ?: false
        connectButton.isEnabled = !isRunning
        disconnectButton.isEnabled = isRunning

        // Enable/disable input fields
        serverAddressEditText.isEnabled = !isRunning
        clientIdEditText.isEnabled = !isRunning
    }

    override fun onResume() {
        super.onResume()

        // Rebind to service if it was already running
        if (!isServiceBound) {
            Intent(this, TunnelProxyService::class.java).also { intent ->
                bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            }
        }
    }

    override fun onPause() {
        super.onPause()

        // Don't unbind here, as we want to keep monitoring the service
        // The service will be unbound when the activity is destroyed
    }

    override fun onDestroy() {
        super.onDestroy()

        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }

        // Optionally stop the service when activity is destroyed
        // Uncomment the line below if you want the service to stop with the activity
        // stopTunnelService()
    }
}