# Android Tunnel Client - 快速测试指南

## 修复的问题
1. ✅ **包名问题**：将所有包名从`com.bugfan.i996.tunnel`改为`com.sean.i996.tunnel`
2. ✅ **依赖问题**：添加了AppCompat和Material Design依赖
3. ✅ **编译器堆栈溢出**：将sealed class改为interface避免递归
4. ✅ **布局简化**：移除了复杂的Material组件，使用标准组件

## 当前文件结构
```
app/src/main/java/com/sean/i996/tunnel/
├── FrameData.kt           # 协议帧定义
├── TunnelClient.kt        # 主隧道客户端
├── TunnelConnection.kt    # 连接管理
├── TunnelProxyService.kt  # 前台服务
└── TunnelActivity.kt      # 用户界面

app/src/main/res/
├── layout/activity_tunnel.xml   # 用户界面布局
└── xml/network_config.xml       # 网络安全配置
```

## 编译测试
现在应该可以正常编译：

```bash
./gradlew assembleDebug
```

## 功能验证
1. **应用启动**：应该能看到"TunnelActivity"界面
2. **连接按钮**：可以输入服务器地址和客户端ID
3. **权限检查**：确保有网络和前台服务权限

## Go服务器配置
确保你的Go服务器正在运行：
```bash
cd tunnel
go run server.go
```

## Android应用配置
- 服务器地址：`127.0.0.1:3333`
- 客户端ID：`android-test-001`

## 已知修复的错误
- ✅ `Unresolved reference 'appcompat'` - 已添加AppCompat依赖
- ✅ `StackOverflowError` - 已修改FrameData结构
- ✅ 包名不匹配 - 已统一为`com.sean.i996.tunnel`

## 下一步
如果编译成功，可以：
1. 在模拟器中运行应用
2. 测试基础连接功能
3. 验证与Go服务器的通信