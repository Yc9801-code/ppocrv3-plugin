#!/usr/bin/env bash
# 下载 PP-OCRv3 推理模型 + 中文字典，并用 paddle_lite_opt 转换成 .nb，
# 最终放入 app/src/main/assets/ppocrv3/ （插件运行时会从这里拷到私有目录）。
#
# 前置：pip install paddlelite   # 提供 paddle_lite_opt 命令行
# 若已用 download_deps.sh 拿到 Paddle Lite 预测库，也可用其中的 tools/opt/paddle_lite_opt。
set -e

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ASSETS="$ROOT/app/src/main/assets/ppocrv3"
TMP="$(mktemp -d)"
mkdir -p "$ASSETS"

BASE="https://paddleocr.bj.bcebos.com/PP-OCRv3/chinese"
KEYS="https://raw.githubusercontent.com/PaddlePaddle/PaddleOCR/release/2.7/ppocr/utils/ppocr_keys_v1.txt"

download_tar() {
  local url="$1" out="$2"
  echo "  下载 $url"
  curl -L -o "$TMP/$out.tar" "$url"
  mkdir -p "$TMP/$out"
  tar -xf "$TMP/$out.tar" -C "$TMP/$out" --strip-components=1
}

echo "[1/4] 下载检测/识别模型（标准版 + tiny 量化版）..."
download_tar "$BASE/ch_PP-OCRv3_det_infer.tar"         det
download_tar "$BASE/ch_PP-OCRv3_rec_infer.tar"         rec
download_tar "$BASE/ch_PP-OCRv3_det_slim_infer.tar"    det_slim
download_tar "$BASE/ch_PP-OCRv3_rec_slim_infer.tar"    rec_slim

echo "[2/4] 下载字典 ppocr_keys_v1.txt ..."
curl -L -o "$ASSETS/ppocr_keys_v1.txt" "$KEYS"

echo "[3/4] 用 paddle_lite_opt 转成 .nb（arm 后端）..."
convert() {
  local mdir="$1" out="$2"
  echo "  转换 -> $out"
  paddle_lite_opt \
    --model_file "$TMP/$mdir/inference.pdmodel" \
    --param_file "$TMP/$mdir/inference.pdiparams" \
    --optimize_out_type naive_buffer \
    --optimize_out "$ASSETS/$out" \
    --valid_targets arm
}
convert det      det.nb
convert rec      rec.nb
convert det_slim det_tiny.nb
convert rec_slim rec_tiny.nb

echo "[4/4] 校验产物 ..."
ls -lh "$ASSETS"

rm -rf "$TMP"
echo "完成。assets/ppocrv3/ 下应有：det.nb rec.nb det_tiny.nb rec_tiny.nb ppocr_keys_v1.txt"
echo "然后用 Android Studio 打开 $ROOT 编译插件 APK 即可。"
