#!/bin/bash

# Go Mobile AAR 编译脚本
# 使用方法: 在 i996-ANDROID 目录下运行 ./build_aar.sh

set -e  # 遇到错误立即退出

echo "========================================="
echo "Go Mobile AAR 编译脚本"
echo "========================================="

# 配置变量（根据你的实际目录结构）
PROJECT_ROOT="$(pwd)"  # i996-ANDROID 目录
TUNNEL_DIR="$PROJECT_ROOT/tunnel"
MOBILECLIENT_DIR="$TUNNEL_DIR/mobileclient"
OUTPUT_AAR="$TUNNEL_DIR/mobileclient.aar"
ANDROID_LIBS_DIR="$PROJECT_ROOT/app/libs"

# 检查目录结构
echo ""
echo "1. 检查目录结构..."
if [ ! -d "$TUNNEL_DIR" ]; then
    echo "❌ 错误: tunnel 目录不存在: $TUNNEL_DIR"
    exit 1
fi

if [ ! -d "$MOBILECLIENT_DIR" ]; then
    echo "❌ 错误: mobileclient 目录不存在: $MOBILECLIENT_DIR"
    exit 1
fi

echo "✅ 目录结构正确"

# 检查 Go 环境
echo ""
echo "2. 检查 Go 环境..."
if ! command -v go &> /dev/null; then
    echo "❌ 错误: 未安装 Go"
    exit 1
fi
echo "✅ Go 版本: $(go version)"

# 检查 gomobile
echo ""
echo "3. 检查 gomobile..."
if ! command -v gomobile &> /dev/null; then
    echo "⚠️  gomobile 未安装，正在安装..."
    go install golang.org/x/mobile/cmd/gomobile@latest
    gomobile init
fi
echo "✅ gomobile 已安装"

# 检查 Java 环境
echo ""
echo "4. 检查 Java 环境..."
if [ -z "$JAVA_HOME" ]; then
    # 尝试使用 Android Studio 的 JDK
    AS_JDK="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
    if [ -d "$AS_JDK" ]; then
        export JAVA_HOME="$AS_JDK"
        export PATH="$JAVA_HOME/bin:$PATH"
        echo "✅ 使用 Android Studio JDK: $JAVA_HOME"
    else
        echo "❌ 错误: JAVA_HOME 未设置，且未找到 Android Studio JDK"
        echo "   请安装 JDK 或设置 JAVA_HOME 环境变量"
        exit 1
    fi
else
    echo "✅ JAVA_HOME: $JAVA_HOME"
fi

# 检查 Android SDK
echo ""
echo "5. 检查 Android SDK..."
ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}"
if [ ! -d "$ANDROID_SDK_ROOT" ]; then
    echo "❌ 错误: Android SDK 未找到"
    echo "   预期位置: $ANDROID_SDK_ROOT"
    exit 1
fi

# 查找 NDK
NDK_PATH="$ANDROID_SDK_ROOT/ndk"
if [ -d "$NDK_PATH" ]; then
    # 使用最新版本的 NDK
    LATEST_NDK=$(ls -1 "$NDK_PATH" | sort -V | tail -1)
    if [ -n "$LATEST_NDK" ]; then
        NDK_PATH="$NDK_PATH/$LATEST_NDK"
        echo "✅ NDK 路径: $NDK_PATH"
    else
        echo "❌ 错误: NDK 目录为空"
        exit 1
    fi
else
    echo "❌ 错误: 未找到 NDK"
    echo "   请在 Android Studio 中安装 NDK"
    exit 1
fi

# 清理旧文件
echo ""
echo "6. 清理旧文件..."
if [ -f "$OUTPUT_AAR" ]; then
    rm "$OUTPUT_AAR"
    echo "✅ 已删除旧的 AAR 文件"
fi

# 编译 AAR
echo ""
echo "7. 编译 AAR..."
echo "   源目录: $MOBILECLIENT_DIR"
echo "   输出文件: $OUTPUT_AAR"

cd "$TUNNEL_DIR"

export NDK_PATH
export ANDROID_API=21

echo "   执行: gomobile bind -target=android -o $OUTPUT_AAR ./mobileclient"

if gomobile bind -v -target=android -o "$OUTPUT_AAR" ./mobileclient; then
    echo "✅ AAR 编译成功"
else
    echo "❌ AAR 编译失败"
    exit 1
fi

# 检查文件大小
if [ -f "$OUTPUT_AAR" ]; then
    FILE_SIZE=$(ls -lh "$OUTPUT_AAR" | awk '{print $5}')
    echo "   文件大小: $FILE_SIZE"
else
    echo "❌ 错误: AAR 文件未生成"
    exit 1
fi

# 复制到 Android 项目
echo ""
echo "8. 复制到 Android 项目..."
if [ -d "$ANDROID_PROJECT_DIR" ]; then
    mkdir -p "$ANDROID_LIBS_DIR"
    cp "$OUTPUT_AAR" "$ANDROID_LIBS_DIR/"
    echo "✅ 已复制到: $ANDROID_LIBS_DIR/mobileclient.aar"
else
    echo "⚠️  警告: Android 项目目录不存在: $ANDROID_PROJECT_DIR"
    echo "   请手动复制 $OUTPUT_AAR 到你的 Android 项目的 app/libs 目录"
fi

# 验证 AAR 内容
echo ""
echo "9. 验证 AAR 内容..."
echo "   包含的类:"
unzip -l "$OUTPUT_AAR" | grep "\.class$" | head -10

echo ""
echo "========================================="
echo "✅ 编译完成！"
echo "========================================="
echo ""
echo "下一步："
echo "1. 确保 cert.pem 放在 Android 项目的 app/src/main/assets/ 目录"
echo "2. 在 Android Studio 中 Sync Project with Gradle Files"
echo "3. Clean 和 Rebuild 项目"
echo "4. 运行应用"
echo ""
echo "AAR 文件位置: $OUTPUT_AAR"