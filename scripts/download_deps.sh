#!/usr/bin/env bash
set -e

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LIBS="$ROOT/app/libs"
JNILIBS="$ROOT/app/src/main/jniLibs"
TMP="$(mktemp -d)"

VERSION="v2.10"

echo "[1/3] 使用 Paddle Lite $VERSION 预测库 ..."

echo "[2/3] 下载并解压 android 预测库 (arm64-v8a / armeabi-v7a) ..."
for ITEM in "arm64-v8a:armv8" "armeabi-v7a:armv7"; do
  OUT_ABI="${ITEM%:*}"
  ASSET_ABI="${ITEM#*:}"
  NAME="inference_lite_lib.android.$ASSET_ABI.gcc.c++_static.tar.gz"
  URL="https://github.com/PaddlePaddle/Paddle-Lite/releases/download/$VERSION/$NAME"
  echo "      下载 $URL"
  curl -L --fail -o "$TMP/$NAME" "$URL"
  tar -xzf "$TMP/$NAME" -C "$TMP"
done

echo "[3/3] 放置 jar 与 so ..."
mkdir -p "$LIBS" "$JNILIBS/arm64-v8a" "$JNILIBS/armeabi-v7a"

JAR="$(find "$TMP" -name 'PaddlePredictor.jar' | head -n1)"
cp "$JAR" "$LIBS/PaddlePredictor.jar"
echo "      -> $LIBS/PaddlePredictor.jar"

SO_64="$(find "$TMP" -path '*android.armv8*/java/so/libpaddle_lite_jni.so' | head -n1)"
SO_32="$(find "$TMP" -path '*android.armv7*/java/so/libpaddle_lite_jni.so' | head -n1)"
cp "$SO_64" "$JNILIBS/arm64-v8a/libpaddle_lite_jni.so"
cp "$SO_32" "$JNILIBS/armeabi-v7a/libpaddle_lite_jni.so"
echo "      -> $JNILIBS/arm64-v8a/libpaddle_lite_jni.so"
echo "      -> $JNILIBS/armeabi-v7a/libpaddle_lite_jni.so"

rm -rf "$TMP"
echo "完成。依赖已就位。"
