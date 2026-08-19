#!/usr/bin/env bash
# 下载 Paddle Lite 预测库（含 Java 推理 jar 与 jni so），放置到工程对应目录。
# 参考：https://www.paddlepaddle.org.cn/lite/develop/user_guides/java_demo.html
set -e

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LIBS="$ROOT/app/libs"
JNILIBS="$ROOT/app/src/main/jniLibs"
TMP="$(mktemp -d)"

echo "[1/3] 获取 Paddle Lite 最新 release 信息 ..."
API="https://api.github.com/repos/PaddlePaddle/Paddle-Lite/releases/latest"
TAG="$(curl -s "$API" | grep -m1 '"tag_name"' | sed -E 's/.*"tag_name": *"([^"]+)".*/\1/')"
echo "      最新版本: $TAG"

echo "[2/3] 下载并解压 android 预测库 (arm64-v8a / armv7) ..."
for ABI in arm64-v8a armv7; do
  NAME="inference_lite_lib.android.$ABI.gcc.c++_static.tar.gz"
  URL="https://github.com/PaddlePaddle/Paddle-Lite/releases/download/$TAG/$NAME"
  echo "      下载 $URL"
  curl -L -o "$TMP/$NAME" "$URL"
  tar -xzf "$TMP/$NAME" -C "$TMP"
done

echo "[3/3] 放置 jar 与 so ..."
mkdir -p "$LIBS" "$JNILIBS/arm64-v8a" "$JNILIBS/armeabi-v7a"

# jar 取其中一个 abi 的即可（java 接口与架构无关）
JAR="$(find "$TMP" -name 'PaddlePredictor.jar' | head -n1)"
cp "$JAR" "$LIBS/PaddlePredictor.jar"
echo "      -> $LIBS/PaddlePredictor.jar"

SO_64="$(find "$TMP" -path '*android.arm64-v8a*/java/so/libpaddle_lite_jni.so' | head -n1)"
SO_32="$(find "$TMP" -path '*android.armv7*/java/so/libpaddle_lite_jni.so' | head -n1)"
cp "$SO_64" "$JNILIBS/arm64-v8a/libpaddle_lite_jni.so"
cp "$SO_32" "$JNILIBS/armeabi-v7a/libpaddle_lite_jni.so"
echo "      -> $JNILIBS/arm64-v8a/libpaddle_lite_jni.so"
echo "      -> $JNILIBS/armeabi-v7a/libpaddle_lite_jni.so"

rm -rf "$TMP"
echo "完成。依赖已就位，可直接用 Android Studio 打开 $ROOT 编译。"
