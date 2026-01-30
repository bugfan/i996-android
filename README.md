# i996 内网穿透 Android 项目

## 项目介绍

基于 i996 内网穿透服务端的 Android 客户端，通过 gomobile 将 Go 代码编译为 AAR 库供 Android 调用。

## 项目结构

```
i996-android/
├── mobile/              # Go 源码和 AAR 构建脚本
│   ├── libi996.go      # Go 源码
│   ├── libi996.aar     # 编译后的 AAR 文件
│   └── build_aar.sh    # AAR 构建脚本
├── app/                # Android 应用
│   └── src/main/
│       └── assets/     # 资源文件（证书等）
└── README_SETUP.md     # Java 环境配置说明
```

## 构建流程

### 1. 构建 AAR（Go 代码）

**首次构建或修改了 Go 代码时执行：**

```bash
cd mobile
./build_aar.sh
```

这会在 `mobile/` 目录生成 `libi996.aar` 文件。

### 2. 准备证书文件

将证书文件放置到：
```
app/src/main/assets/cert.pem
```

### 3. 编译 APK

**方式一：命令行构建**
```bash
./gradlew :app:assembleDebug
```

**方式二：Android Studio**
1. 打开项目
2. 点击 "Build > Make Project"
3. 点击 "Build > Build Bundle(s) / APK(s) > Build APK(s)"

生成的 APK 位于：`app/build/outputs/apk/debug/app-debug.apk`

## 常见问题

### Java 版本错误

如果构建时提示 "requires Java 17"，请参考 [README_SETUP.md](README_SETUP.md) 配置 Java 17 环境。

### AAR 相关错误

如果编译时提示找不到 `I996Client` 或 `Logger` 类：
1. 确认 `mobile/libi996.aar` 文件存在
2. 确认文件大小正常（约 19MB）
3. 尝试重新构建 AAR

## 开发说明

- **修改 Go 代码**：修改 `mobile/libi996.go` 后，需要重新运行 `build_aar.sh`
- **修改 Android 代码**：直接修改 `app/src/` 下的文件，重新构建 APK 即可
- **AAR 文件**：已编译好的 AAR 文件已包含在项目中，通常无需重新构建

## 依赖

- Go 1.21+
- gomobile
- Android SDK
- Android NDK
- Java 17
