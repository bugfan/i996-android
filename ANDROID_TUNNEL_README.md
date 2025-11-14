# Android Tunnel Client 实现说明

这个实现将你的Go隧道客户端移植到了Android平台，使用Kotlin重新实现了相同的功能。

## 核心组件

### 1. TunnelClient.kt
主要隧道客户端类，负责：
- TLS连接建立
- 协议帧处理
- 连接管理
- 数据读写循环

**主要功能：**
- 与Go服务器建立TLS连接
- 解析和发送协议帧
- 管理多个隧道连接
- 处理心跳和错误恢复

### 2. TunnelConnection.kt
单个隧道连接管理类，负责：
- 目标服务器连接
- 数据转发
- 流量控制
- 连接状态管理

**主要功能：**
- 连接到目标服务器（如HTTP网站）
- 在隧道和目标服务器之间转发数据
- 处理数据确认和流量控制
- 管理连接生命周期

### 3. TunnelProxyService.kt
Android前台服务，负责：
- 后台运行隧道服务
- 管理隧道生命周期
- 提供状态监控
- 处理连接请求

**主要功能：**
- 作为前台服务运行，避免被系统杀死
- 管理所有活跃的隧道连接
- 提供连接状态和数据统计
- 与Activity通信

### 4. TunnelActivity.kt
用户界面，负责：
- 配置隧道参数
- 控制连接状态
- 显示连接信息
- 用户交互

## 协议实现

实现了与Go版本完全相同的二进制协议：

### 帧结构
```
[ConnectionID:8字节] [Command:8字节] [Data:变长]
```

### 支持的命令
- `COMMAND_DIAL (128)`: 请求建立新连接
- `COMMAND_ACCEPT (129)`: 确认接受连接
- `COMMAND_DATA (0)`: 数据传输
- `COMMAND_DATA_CONFIRM (1)`: 数据确认
- `COMMAND_CLOSE (130)`: 关闭连接
- `COMMAND_PING (132)`: 心跳
- `COMMAND_PONG (133)`: 心跳响应

## 使用方法

### 1. 启动Go服务器
```bash
cd tunnel
go run server.go
```

### 2. 配置Android应用
- 服务器地址：`127.0.0.1:3333`（或你的公网服务器地址）
- 客户端ID：唯一标识符（如`android-tunnel-001`）

### 3. 建立连接
- 点击"Connect"按钮
- 等待连接状态变为"Connected"

### 4. 测试隧道
服务器端可以通过HTTP代理访问网站，流量会通过Android设备转发：

```bash
curl -x http://localhost:4444 http://www.google.com
```

## 安全特性

### TLS配置
- 支持TLS 1.2/1.3
- 配置了强加密套件
- 开发环境下接受所有证书（生产环境需要修改）

### 网络权限
- INTERNET：网络连接
- FOREGROUND_SERVICE：前台服务
- WAKE_LOCK：保持设备唤醒
- ACCESS_NETWORK_STATE：网络状态检测

### 网络安全配置
- 允许明文流量（开发环境）
- 配置本地网络信任
- 支持用户证书

## 性能特性

### 连接管理
- 异步处理所有网络操作
- 使用线程池管理连接
- 实现了流量控制和背压

### 内存管理
- 缓冲区复用
- 及时关闭无用连接
- 避免内存泄漏

### 错误处理
- 自动重连机制
- 连接超时处理
- 资源清理保证

## 扩展功能

### 可添加的功能
1. **证书验证**：生产环境的证书验证
2. **压缩支持**：数据压缩以减少流量
3. **加密增强**：端到端加密
4. **多路复用**：更好的连接复用
5. **统计功能**：详细的流量和连接统计

### 配置选项
1. **服务器配置**：支持多服务器配置
2. **代理配置**：SOCKS5、HTTP代理支持
3. **路由规则**：基于域名的路由
4. **流量控制**：限速和配额管理

## 构建和部署

### Gradle配置
```gradle
dependencies {
    implementation 'androidx.core:core-ktx:1.9.0'
    implementation 'androidx.appcompat:appcompat:1.6.1'
    implementation 'com.google.android.material:material:1.8.0'
    implementation 'androidx.constraintlayout:constraintlayout:2.1.4'
    implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.4'
}
```

### 权限配置
已在AndroidManifest.xml中配置了所需权限和网络安全设置。

## 注意事项

### 生产环境部署
1. 修改证书验证逻辑
2. 添加用户认证
3. 实现配置加密存储
4. 添加日志记录和监控

### 兼容性
- 最低Android版本：API 21 (Android 5.0)
- 目标版本：API 33 (Android 13)
- 支持网络：WiFi、移动数据、VPN

### 性能优化建议
1. 使用更高效的ByteBuffer
2. 实现连接池
3. 添加缓存机制
4. 优化数据序列化

这个实现提供了与Go版本完全兼容的隧道功能，可以让你的Android设备作为隧道客户端工作。