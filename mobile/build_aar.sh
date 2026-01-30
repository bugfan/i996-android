#!/bin/bash

# 构建 Android AAR 的脚本
# 使用方法: 在 mobile 目录下运行 ./build_aar.sh

set -e

echo "========================================="
echo "构建 Android AAR (libi996)"
echo "========================================="

# 1. 检查 NDK
echo ""
echo "1. 检查 Android NDK..."
ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}"
NDK_FOUND=false

# 检查多个可能的 NDK 位置
NDK_PATHS=(
    "$ANDROID_SDK_ROOT/ndk"
)

for ndk_dir in "${NDK_PATHS[@]}"; do
    if [ -d "$ndk_dir" ]; then
        # 查找最新的 NDK 版本
        LATEST_NDK=$(ls -1 "$ndk_dir" 2>/dev/null | sort -V | tail -1)
        if [ -n "$LATEST_NDK" ]; then
            export ANDROID_NDK_HOME="$ndk_dir/$LATEST_NDK"
            NDK_FOUND=true
            echo "   ✅ 找到 NDK: $ANDROID_NDK_HOME"
            break
        fi
    fi
done

if [ "$NDK_FOUND" = false ]; then
    echo "   ❌ 未找到 Android NDK"
    echo ""
    echo "═══════════════════════════════════════════════════"
    echo "  需要安装 Android NDK 才能构建 AAR"
    echo "═══════════════════════════════════════════════════"
    echo ""
    echo "📱 安装步骤（推荐方式）："
    echo ""
    echo "  1. 打开 Android Studio"
    echo "  2. Preferences/Settings (macOS: ⌘+,)"
    echo "  3. Appearance & Behavior > System Settings > Android SDK"
    echo "  4. 点击 'SDK Tools' 标签页"
    echo "  5. 勾选以下选项："
    echo "     ☑ 'NDK (Side by side)'"
    echo "     ☑ 'CMake'"
    echo "  6. 点击 'Apply' 或 'OK' 开始下载（约 1-2GB）"
    echo ""
    echo "💡 或者：从公司电脑复制已有的 AAR 文件"
    echo "   如果公司电脑已经编译过，直接复制以下文件："
    echo "   mobile/libi996.aar"
    echo ""
    echo "───────────────────────────────────────────────────"
    echo ""
    exit 1
fi

# 2. 确保 gomobile 已安装
echo ""
echo "2. 检查 gomobile..."
if ! command -v gomobile &> /dev/null; then
    echo "   正在安装 gomobile..."
    go install golang.org/x/mobile/cmd/gomobile@latest
    go install golang.org/x/mobile/cmd/gobind@latest
    echo "   正在初始化 gomobile..."
    gomobile init
fi
echo "   ✅ gomobile 已就绪"

# 3. 检查 Go 源文件
echo ""
echo "3. 检查源文件..."
if [ ! -f "libi996.go" ]; then
    echo "   ❌ 错误: 找不到 libi996.go"
    echo "   请确保在 mobile 目录下运行此脚本"
    exit 1
fi
echo "   ✅ 找到 libi996.go"

# 4. 构建 AAR
echo ""
echo "4. 开始构建 AAR..."
echo "   源文件: libi996.go"
echo "   输出: libi996.aar"
echo "   包名: com.sean.i996.libi996"
echo "   NDK: $ANDROID_NDK_HOME"
echo ""

gomobile bind -v -target=android \
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
    echo "📦 AAR 文件: $(pwd)/libi996.aar"
    echo "📏 文件大小: $(ls -lh libi996.aar | awk '{print $5}')"
    echo ""

    # 清理 sources jar（可选）
    if [ -f "libi996-sources.jar" ]; then
        echo "🧹 清理不必要的文件..."
        rm -f libi996-sources.jar
        echo "   ✅ 已删除 libi996-sources.jar（不必需）"
    fi

    echo ""
    echo "🚀 下一步："
    echo "   cd .."
    echo "   ./gradlew :app:assembleDebug"
    echo ""
else
    echo ""
    echo "========================================="
    echo "❌ 构建失败"
    echo "========================================="
    echo ""
    echo "💡 故障排查："
    echo "   1. 检查 Go 版本: go version"
    echo "   2. 检查 NDK 路径: echo \$ANDROID_NDK_HOME"
    echo "   3. 查看详细错误: 删除 -v 参数后重新运行"
    echo ""
    exit 1
fi
