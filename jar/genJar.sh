#!/bin/bash
bash ./gradlew assembleDebug assembleRelease

set -e  # 如果有命令失败则中断脚本
BASE_DIR="$(cd "$(dirname "$0")"; pwd)"

# 删除旧文件
rm -f "$BASE_DIR/custom_spider.jar"
rm -rf "$BASE_DIR/Smali_classes"

# 反编译 APK
java -jar "$BASE_DIR/3rd/apktool_2.11.0.jar" d -f --only-main-classes "$BASE_DIR/../app/build/outputs/apk/release/app-release-unsigned.apk" -o "$BASE_DIR/Smali_classes"

# 清理旧的模块
rm -rf "$BASE_DIR/spider.jar/smali/com/github/catvod"
rm -rf "$BASE_DIR/spider.jar/smali/org/slf4j"

# 创建目录
mkdir -p "$BASE_DIR/spider.jar/smali/com/github/catvod"
mkdir -p "$BASE_DIR/spider.jar/smali/org/slf4j"

# 移动提取的 smali 文件
mv "$BASE_DIR/Smali_classes/smali" "$BASE_DIR/spider.jar/smali"
#mv "$BASE_DIR/Smali_classes/smali/org" "$BASE_DIR/spider.jar/smali/"

# 重新打包
java -jar "$BASE_DIR/3rd/apktool_2.11.0.jar" b "$BASE_DIR/spider.jar" -c

# 移动 dex.jar 成为目标 jar
mv "$BASE_DIR/spider.jar/dist/dex.jar" "$BASE_DIR/custom_spider.jar"

# 生成 MD5（mac 上 certUtil 不可用，用 openssl 代替）
md5 -q "$BASE_DIR/custom_spider.jar" > "$BASE_DIR/custom_spider.jar.md5"

# 清理中间文件
rm -rf "$BASE_DIR/spider.jar/build"
rm -rf "$BASE_DIR/spider.jar/smali"
rm -rf "$BASE_DIR/spider.jar/dist"
rm -rf "$BASE_DIR/Smali_classes"