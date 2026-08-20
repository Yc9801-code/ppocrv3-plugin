本目录用于放置 PP-OCRv3 推理所需的模型与字典。
运行 scripts/download_models.sh 后会自动生成以下文件（不要手动提交 .nb 大文件）：

  det.nb              检测模型（PP-OCRv3 标准版）
  rec.nb              识别模型（PP-OCRv3 标准版）
  det_tiny.nb         检测模型（PP-OCRv3-tiny 量化版，init(true) 使用）
  rec_tiny.nb         识别模型（PP-OCRv3-tiny 量化版，init(true) 使用）
  ppocr_keys_v1.txt   中文字典（6623 字，CTC 解码用）

说明：
  - 模型为 Paddle Lite naive_buffer 格式（.nb），由 PaddleOCR 推理模型经 paddle_lite_opt 转换得到。
  - 插件首次 init() 时，会把本目录的 .nb / .txt 拷贝到应用私有缓存目录再加载。
  - aaptOptions.noCompress 已对 nb/txt 关闭压缩，安装后无需再解压。
