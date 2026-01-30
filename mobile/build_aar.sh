#!/bin/bash

# 构建 Android AAR 的脚本
# 使用方法: 在 mobile 目录下运行 ./build_aar.sh

set -e  # 遇到错误立即退出

echo "========================================="
echo "构建 Android AAR (libi996)"
echo "========================================="

# 1. 检查 NDK
echo ""
echo "1. 检查 Android NDK..."
ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}"

if [ ! -d "$ANDROID_SDK_ROOT/ndk" ]; then
    echo "   ❌ 错误: 未找到 Android NDK"
    echo ""
    echo "   请先安装 NDK："
    echo "   方式一（推荐）: Android Studio > Preferences > Appearance & Behavior >"
    echo "                   System Settings > Android SDK > SDK Tools > 勾选 NDK"
    echo ""
    echo "   方式二: 命令行安装"
    echo "           sdkmanager \"ndk;26.1.10909125\""
    echo ""
    exit 1
fi

# 找到最新的 NDK 版本
NDK_PATH=$(ls -1 "$ANDROID_SDK_ROOT/ndk" | sort -V | tail -1)
if [ -z "$NDK_PATH" ]; then
    echo "   ❌ 错误: NDK 目录为空"
    exit 1
fi

echo "   ✅ 找到 NDK: $ANDROID_SDK_ROOT/ndk/$NDK_PATH"
export ANDROID_NDK_HOME="$ANDROID_SDK_ROOT/ndk/$NDK_PATH"

# 2. 确保 gomobile 已安装
echo ""
echo "2. 检查 gomobile..."
if ! command -v gomobile &> /dev/null; then
    echo "   安装 gomobile..."
    go install golang.org/x/mobile/cmd/gomobile@latest
    go install golang.org/x/mobile/cmd/gobind@latest
    gomobile init
fi
echo "   ✅ gomobile 已就绪"

# 3. 构建 AAR
echo ""
echo "3. 开始构建 AAR..."
echo "   源文件: libi996.go"
echo "   输出: libi996.aar"
echo "   包名: com.sean.i996.libi996"
echo "   NDK: $ANDROID_NDK_HOME"

gomobile bind -target=android \
    -androidapi 21 \
    -o ./libi996.aar \
    -javapkg=com.sean.i996.libi996 \
    .

if [ $? -eq 0 ]; then
    echo ""
    echo "========================================="
    echo "✅ 构建成功！"
    echo "========================================="
    echo ""
    echo "AAR 文件: $(pwd)/libi996.aar"
    echo "文件大小: $(ls -lh libi996.aar | awk '{print $5}')"
    echo ""
    echo "APK 项目会自动引用此文件，无需手动复制。"
    echo "现在可以运行: ./gradlew :app:assembleDebug"
    echo ""
else
    echo ""
    echo "❌ 构建失败"
    exit 1
fi
