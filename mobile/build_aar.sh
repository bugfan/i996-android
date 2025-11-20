#!/bin/bash

# 构建 Android AAR 的脚本

echo "开始构建 Android AAR..."

# 1. 清理并重新安装 gomobile
echo "安装/更新 gomobile..."
go install golang.org/x/mobile/cmd/gomobile@latest
go install golang.org/x/mobile/cmd/gobind@latest

# 2. 初始化 gomobile（这会下载必要的依赖）
echo "初始化 gomobile..."
gomobile init

# 3. 确保 mobile/bind 包存在
echo "检查依赖..."
go get golang.org/x/mobile/bind

# 2. 设置输出目录
OUTPUT_DIR="./mobile/android-libs"
mkdir -p $OUTPUT_DIR

# 3. 构建 AAR
echo "构建 AAR 文件..."
gomobile bind -target=android \
    -androidapi 21 \
    -o $OUTPUT_DIR/mobileclient.aar \
    -javapkg=com.sean.i996 \
    ./mobile

if [ $? -eq 0 ]; then
    echo "✅ 构建成功！"
    echo "AAR 文件位置: $OUTPUT_DIR/mobileclient.aar"
    echo ""
    echo "接下来的步骤："
    echo "1. 将 mobileclient.aar 复制到 Android 项目的 app/libs/ 目录"
    echo "2. 在 app/build.gradle 中添加依赖"
else
    echo "❌ 构建失败"
    exit 1
fi