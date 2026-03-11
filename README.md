# i996 Android Client

Kotlin 实现的 i996 内网穿透 Android 客户端，完全复刻 Go 客户端逻辑。

## 项目结构

```
app/src/main/
├── java/me/i996/client/
│   ├── mux/
│   │   ├── Session.kt        # TCP 会话多路复用（对应 mux.go）
│   │   ├── Stream.kt         # 逻辑流（net.Conn 的等价物）
│   │   └── TunnelClient.kt   # 客户端主逻辑（对应 tunnel.go Client）
│   ├── service/
│   │   ├── TunnelService.kt  # Android 前台服务（保活）
│   │   └── BootReceiver.kt   # 开机自启
│   ├── ui/
│   │   └── MainActivity.kt   # 主界面
│   └── util/
│       ├── LogBuffer.kt      # 环形日志缓冲区
│       └── Prefs.kt          # SharedPreferences 封装
└── res/
    ├── layout/activity_main.xml
    └── ...
```

## 集成步骤

### 1. 放入 CA 证书

将服务端 CA 证书（PEM 格式）复制为：
```
app/src/main/assets/ca.crt
```

如果你的证书是 `cert.Cert` 变量（Go 代码中），把它的内容保存到这个文件即可。

### 2. 修改默认服务器地址

编辑 `util/Prefs.kt`：
```kotlin
private const val DEFAULT_SERVER = "i996.me:8225"  // 改成你手机专用的端口
```

### 3. 构建 APK

```bash
cd i996android
./gradlew assembleRelease
```

APK 在 `app/build/outputs/apk/release/app-release.apk`

**注意**：正式发布前记得配置签名，替换 `build.gradle` 里的 `signingConfig signingConfigs.debug`。

---

## 功能说明

### 保活机制

| 机制 | 说明 |
|------|------|
| `ForegroundService` | 系统通知栏持久显示，最高优先级保活 |
| `START_STICKY` | 被系统杀死后自动重启 |
| `onTaskRemoved` | 用户滑走 App 时通过 AlarmManager 3秒后重启服务 |
| `BOOT_COMPLETED` | 开机后自动连接（如果 token 已保存） |
| 指数退避重连 | 2s → 4s → 8s ... 最大 2 分钟 |
| Ping/Pong | 每 10s 一次，30s 无响应则断开重连 |

### 与 Go 客户端的对应关系

```
Go                          Kotlin
────────────────────────────────────────────────
Session (mux.go)        →   Session.kt
Stream (mux.go)         →   Stream.kt
Client.Run()            →   TunnelClient.run()
Client.handleStream()   →   TunnelClient.handleStream()
pipe.PrintHTTP()        →   TunnelClient.pipeWithSniff()
for { c.Run(); sleep }  →   TunnelClient.reconnectLoop()
```

### 控制消息

全部支持：`auth`, `auth_result`, `signal`, `reload`, `kick`, `break`, `reset`

---

## 给服务端开新端口

在你的服务端 `tunnel/server.go` 旁边新增一个监听（示意）：

```go
// 手机专用端口
go tunnel.Listen("0.0.0.0:8225")
```

或者复用同一个 `conn.Server`，就不需要改 authFn，token 完全共用。

---

## 电池优化白名单

部分厂商（MIUI、EMUI、ColorOS）会激进杀后台，建议在 App 首次启动时引导用户加入电池优化白名单：

```kotlin
// 在 MainActivity.onCreate 中加入：
val pm = getSystemService(POWER_SERVICE) as PowerManager
if (!pm.isIgnoringBatteryOptimizations(packageName)) {
    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
        data = Uri.parse("package:$packageName")
    })
}
```

---

## 注意事项

1. **CA 证书**：必须把 `cert.Cert`（你的 Go 代码里的）导出为 PEM 文件放到 `assets/ca.crt`
2. **Android 14+**：`foregroundServiceType="dataSync"` 已在 Manifest 中配置
3. **通知权限**：Android 13+ 需要运行时申请 `POST_NOTIFICATIONS`，可在 MainActivity 里加一句 `requestPermissions`
4. **混淆**：proguard-rules.pro 已保留必要类，release 构建安全
