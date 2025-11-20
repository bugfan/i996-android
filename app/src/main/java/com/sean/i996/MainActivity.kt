package com.sean.i996

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.sean.i996.mobile.Mobile
import com.sean.i996.mobile.LogCallback
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import android.widget.ScrollView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private var i996Client: com.sean.i996.mobile.I996Client? = null
    private lateinit var tokenInput: EditText
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var clearLogButton: Button
    private lateinit var statusText: TextView
    private lateinit var logTextView: TextView
    private lateinit var logScrollView: ScrollView

    private val logBuilder = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tokenInput = findViewById(R.id.tokenInput)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        clearLogButton = findViewById(R.id.clearLogButton)
        statusText = findViewById(R.id.statusText)
        logTextView = findViewById(R.id.logTextView)
        logScrollView = findViewById(R.id.logScrollView)

        startButton.setOnClickListener {
            startClient()
        }

        stopButton.setOnClickListener {
            stopClient()
        }

        clearLogButton.setOnClickListener {
            clearLog()
        }

        updateUI()
    }

    private fun startClient() {
        val token = tokenInput.text.toString().trim()

        if (token.isEmpty()) {
            Toast.makeText(this, "请输入 Token", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            if (i996Client == null) {
                // 创建客户端实例
                i996Client = Mobile.newI996Client(token)

                // 设置日志回调
                i996Client?.setLogCallback(object : LogCallback {
                    override fun onLog(message: String) {
                        // 在主线程更新 UI
                        runOnUiThread {
                            appendLog(message)
                        }
                    }
                })
            } else {
                // 如果已存在，更新 token
                i996Client?.setToken(token)
            }

            // 启动客户端
            i996Client?.start()

            appendLog("[系统] 正在连接服务器...")
            Toast.makeText(this, "客户端已启动", Toast.LENGTH_SHORT).show()
            updateUI()

        } catch (e: Exception) {
            val errorMsg = "启动失败: ${e.message}"
            appendLog("[错误] $errorMsg")
            Toast.makeText(this, errorMsg, Toast.LENGTH_SHORT).show()
            e.printStackTrace()
        }
    }

    private fun stopClient() {
        try {
            appendLog("[系统] 正在停止客户端...")
            i996Client?.stop()

            // 延迟一下，让停止操作完成
            lifecycleScope.launch {
                kotlinx.coroutines.delay(1000)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "客户端已停止", Toast.LENGTH_SHORT).show()
                    updateUI()
                }
            }
        } catch (e: Exception) {
            val errorMsg = "停止失败: ${e.message}"
            appendLog("[错误] $errorMsg")
            Toast.makeText(this, errorMsg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun appendLog(message: String) {
        // 添加时间戳
        val timestamp = java.text.SimpleDateFormat(
            "HH:mm:ss",
            java.util.Locale.getDefault()
        ).format(java.util.Date())

        val logMessage = "[$timestamp] $message\n"
        logBuilder.append(logMessage)

        // 限制日志长度（保留最后 10000 个字符）
        if (logBuilder.length > 10000) {
            logBuilder.delete(0, logBuilder.length - 10000)
        }

        logTextView.text = logBuilder.toString()

        // 自动滚动到底部
        logScrollView.post {
            logScrollView.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    private fun clearLog() {
        logBuilder.clear()
        logTextView.text = ""
    }

    private fun updateUI() {
        val isRunning = i996Client?.isRunning() ?: false

        statusText.text = if (isRunning) "运行中 🟢" else "已停止 🔴"
        startButton.isEnabled = !isRunning
        stopButton.isEnabled = isRunning
        tokenInput.isEnabled = !isRunning
    }

    override fun onDestroy() {
        super.onDestroy()
        appendLog("[系统] Activity 正在销毁...")

        // 强制停止客户端
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                i996Client?.forceStop()
                withContext(Dispatchers.Main) {
                    appendLog("[系统] Activity 已销毁，客户端已停止")
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // 当 Activity 进入后台时，可以选择是否停止客户端
        // 如果想让客户端在后台继续运行，注释掉下面的代码
        // 如果想在后台也停止，取消注释
        /*
        lifecycleScope.launch(Dispatchers.IO) {
            i996Client?.forceStop()
        }
        */
    }
}

// ============================================
// Java 版本
// ============================================

/*
import com.sean.i996.mobile.Mobile;
import com.sean.i996.mobile.I996Client;
import com.sean.i996.mobile.LogCallback;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

public class MainActivity extends AppCompatActivity {

    private I996Client i996Client;
    private EditText tokenInput;
    private Button startButton;
    private Button stopButton;
    private Button clearLogButton;
    private TextView statusText;
    private TextView logTextView;
    private ScrollView logScrollView;
    private StringBuilder logBuilder = new StringBuilder();
    private ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tokenInput = findViewById(R.id.tokenInput);
        startButton = findViewById(R.id.startButton);
        stopButton = findViewById(R.id.stopButton);
        clearLogButton = findViewById(R.id.clearLogButton);
        statusText = findViewById(R.id.statusText);
        logTextView = findViewById(R.id.logTextView);
        logScrollView = findViewById(R.id.logScrollView);

        startButton.setOnClickListener(v -> startClient());
        stopButton.setOnClickListener(v -> stopClient());
        clearLogButton.setOnClickListener(v -> clearLog());

        updateUI();
    }

    private void startClient() {
        String token = tokenInput.getText().toString().trim();

        if (token.isEmpty()) {
            Toast.makeText(this, "请输入 Token", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            if (i996Client == null) {
                i996Client = Mobile.newI996Client(token);

                // 设置日志回调
                i996Client.setLogCallback(new LogCallback() {
                    @Override
                    public void onLog(String message) {
                        runOnUiThread(() -> appendLog(message));
                    }
                });
            } else {
                i996Client.setToken(token);
            }

            i996Client.start();
            appendLog("[系统] 正在连接服务器...");
            Toast.makeText(this, "客户端已启动", Toast.LENGTH_SHORT).show();
            updateUI();

        } catch (Exception e) {
            String errorMsg = "启动失败: " + e.getMessage();
            appendLog("[错误] " + errorMsg);
            Toast.makeText(this, errorMsg, Toast.LENGTH_SHORT).show();
            e.printStackTrace();
        }
    }

    private void stopClient() {
        try {
            appendLog("[系统] 正在停止客户端...");

            executor.execute(() -> {
                try {
                    if (i996Client != null) {
                        i996Client.forceStop();
                    }

                    Thread.sleep(500);

                    runOnUiThread(() -> {
                        appendLog("[系统] 客户端已完全停止");
                        Toast.makeText(this, "客户端已停止", Toast.LENGTH_SHORT).show();
                        updateUI();
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> {
                        String errorMsg = "停止失败: " + e.getMessage();
                        appendLog("[错误] " + errorMsg);
                        Toast.makeText(this, errorMsg, Toast.LENGTH_SHORT).show();
                    });
                }
            });

        } catch (Exception e) {
            String errorMsg = "停止失败: " + e.getMessage();
            appendLog("[错误] " + errorMsg);
            Toast.makeText(this, errorMsg, Toast.LENGTH_SHORT).show();
        }
    }

    private void appendLog(String message) {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
        String timestamp = sdf.format(new Date());
        String logMessage = "[" + timestamp + "] " + message + "\n";

        logBuilder.append(logMessage);

        if (logBuilder.length() > 10000) {
            logBuilder.delete(0, logBuilder.length() - 10000);
        }

        logTextView.setText(logBuilder.toString());
        logScrollView.post(() -> logScrollView.fullScroll(ScrollView.FOCUS_DOWN));
    }

    private void clearLog() {
        logBuilder.setLength(0);
        logTextView.setText("");
    }

    private void updateUI() {
        boolean isRunning = i996Client != null && i996Client.isRunning();

        statusText.setText(isRunning ? "运行中 🟢" : "已停止 🔴");
        startButton.setEnabled(!isRunning);
        stopButton.setEnabled(isRunning);
        tokenInput.setEnabled(!isRunning);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        appendLog("[系统] Activity 正在销毁...");

        executor.execute(() -> {
            try {
                if (i996Client != null) {
                    i996Client.forceStop();
                }
                runOnUiThread(() -> appendLog("[系统] Activity 已销毁，客户端已停止"));
            } catch (Exception e) {
                e.printStackTrace();
            }
        });

        executor.shutdown();
    }
}

// ============================================
// Java 版本
// ============================================

/*
import com.sean.i996.mobile.Mobile;
import com.sean.i996.mobile.I996Client;
import com.sean.i996.mobile.LogCallback;

public class MainActivity extends AppCompatActivity {

    private I996Client i996Client;
    private EditText tokenInput;
    private Button startButton;
    private Button stopButton;
    private Button clearLogButton;
    private TextView statusText;
    private TextView logTextView;
    private ScrollView logScrollView;
    private StringBuilder logBuilder = new StringBuilder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tokenInput = findViewById(R.id.tokenInput);
        startButton = findViewById(R.id.startButton);
        stopButton = findViewById(R.id.stopButton);
        clearLogButton = findViewById(R.id.clearLogButton);
        statusText = findViewById(R.id.statusText);
        logTextView = findViewById(R.id.logTextView);
        logScrollView = findViewById(R.id.logScrollView);

        startButton.setOnClickListener(v -> startClient());
        stopButton.setOnClickListener(v -> stopClient());
        clearLogButton.setOnClickListener(v -> clearLog());

        updateUI();
    }

    private void startClient() {
        String token = tokenInput.getText().toString().trim();

        if (token.isEmpty()) {
            Toast.makeText(this, "请输入 Token", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            if (i996Client == null) {
                i996Client = Mobile.newI996Client(token);

                // 设置日志回调
                i996Client.setLogCallback(new LogCallback() {
                    @Override
                    public void onLog(String message) {
                        runOnUiThread(() -> appendLog(message));
                    }
                });
            } else {
                i996Client.setToken(token);
            }

            i996Client.start();
            appendLog("[系统] 正在连接服务器...");
            Toast.makeText(this, "客户端已启动", Toast.LENGTH_SHORT).show();
            updateUI();

        } catch (Exception e) {
            String errorMsg = "启动失败: " + e.getMessage();
            appendLog("[错误] " + errorMsg);
            Toast.makeText(this, errorMsg, Toast.LENGTH_SHORT).show();
            e.printStackTrace();
        }
    }

    private void stopClient() {
        try {
            appendLog("[系统] 正在停止客户端...");
            if (i996Client != null) {
                i996Client.stop();
            }

            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                Toast.makeText(this, "客户端已停止", Toast.LENGTH_SHORT).show();
                updateUI();
            }, 1000);

        } catch (Exception e) {
            String errorMsg = "停止失败: " + e.getMessage();
            appendLog("[错误] " + errorMsg);
            Toast.makeText(this, errorMsg, Toast.LENGTH_SHORT).show();
        }
    }

    private void appendLog(String message) {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
        String timestamp = sdf.format(new Date());
        String logMessage = "[" + timestamp + "] " + message + "\n";

        logBuilder.append(logMessage);

        if (logBuilder.length() > 10000) {
            logBuilder.delete(0, logBuilder.length() - 10000);
        }

        logTextView.setText(logBuilder.toString());
        logScrollView.post(() -> logScrollView.fullScroll(ScrollView.FOCUS_DOWN));
    }

    private void clearLog() {
        logBuilder.setLength(0);
        logTextView.setText("");
    }

    private void updateUI() {
        boolean isRunning = i996Client != null && i996Client.isRunning();

        statusText.setText(isRunning ? "运行中 🟢" : "已停止 🔴");
        startButton.setEnabled(!isRunning);
        stopButton.setEnabled(isRunning);
        tokenInput.setEnabled(!isRunning);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (i996Client != null) {
            i996Client.stop();
        }
        appendLog("[系统] Activity 已销毁，客户端已停止");
    }
}
*/