package com.kkx.ppocrv3.ocr;

import android.graphics.Bitmap;

import com.baidu.paddle.lite.MobileConfig;
import com.baidu.paddle.lite.PaddlePredictor;
import com.baidu.paddle.lite.PowerMode;
import com.baidu.paddle.lite.Tensor;

/**
 * CRNN/SVTR 文本识别（PP-OCRv3 rec 模型）。
 * 预处理：裁剪框 -> 高固定 48、宽按宽高比、最长 320、右侧补黑 -> BGR + 0.5 归一化。
 * 后处理：CTC 贪婪解码（去重 + 去 blank）。
 */
public final class CrnnRecognizer {

    // 识别归一化（PaddleOCR rec 默认）：BGR 顺序，mean/std 均为 0.5
    private static final float[] MEAN = {0.5f, 0.5f, 0.5f};
    private static final float[] STD = {0.5f, 0.5f, 0.5f};

    private static final int IMG_H = 48;
    private static final int MAX_W = 320;

    private final Vocab vocab;
    private PaddlePredictor predictor;

    public CrnnRecognizer(Vocab vocab) {
        this.vocab = vocab;
    }

    public void init(String modelPath) {
        MobileConfig config = new MobileConfig();
        config.setModelFromFile(modelPath);
        config.setPowerMode(PowerMode.LITE_POWER_HIGH);
        config.setThreads(4);
        predictor = PaddlePredictor.createPaddlePredictor(config);
    }

    public static class RecResult {
        public final String text;
        public final float confidence;

        public RecResult(String text, float confidence) {
            this.text = text;
            this.confidence = confidence;
        }
    }

    /** 对裁剪后的单行文本图做识别 */
    public RecResult recognize(Bitmap crop) {
        int cw = Math.max(crop.getWidth(), 1);
        int ch = Math.max(crop.getHeight(), 1);
        float ratio = (float) cw / ch;
        int newW = (int) (IMG_H * ratio);
        if (newW > MAX_W) newW = MAX_W;
        if (newW < 1) newW = 1;

        Bitmap resized = Bitmap.createScaledBitmap(crop, newW, IMG_H, true);
        Bitmap canvas = Bitmap.createBitmap(MAX_W, IMG_H, Bitmap.Config.ARGB_8888);
        new android.graphics.Canvas(canvas).drawBitmap(resized, 0, 0, null);
        if (resized != crop) resized.recycle();

        float[] chw = ImageUtils.toFloatCHW(canvas, MAX_W, IMG_H, MEAN, STD, 1.0f / 255.0f);
        if (canvas != crop) canvas.recycle();

        Tensor input = predictor.getInput(0);
        input.resize(new long[]{1, 3, IMG_H, MAX_W});
        input.setData(chw);
        predictor.run();
        Tensor out = predictor.getOutput(0);
        float[] data = out.getFloatData();
        long[] shape = out.shape(); // 末尾两维为 [a, b]（可能为 4 维 [1,1,a,b]）

        int a = (int) shape[shape.length - 2];
        int b = (int) shape[shape.length - 1];
        // 判定类别维：等于词表+blank(6624) 的那一维为 C
        int blank = vocab.blankIndex();
        int C, T, layout; // layout: 0 -> [T,C]; 1 -> [C,T]
        if (a == blank + 1) { C = a; T = b; layout = 1; }
        else if (b == blank + 1) { C = b; T = a; layout = 0; }
        else if (a > b) { C = a; T = b; layout = 1; }
        else { C = b; T = a; layout = 0; }

        StringBuilder sb = new StringBuilder();
        int prev = -1;
        float confSum = 0;
        for (int t = 0; t < T; t++) {
            int best = 0;
            float bestV = -Float.MAX_VALUE;
            for (int c = 0; c < C; c++) {
                float v = (layout == 0) ? data[t * C + c] : data[c * T + t];
                if (v > bestV) { bestV = v; best = c; }
            }
            confSum += bestV;
            if (best != blank && best != prev) {
                sb.append(vocab.charAt(best));
            }
            prev = best;
        }
        float confidence = (T > 0) ? confSum / T : 0f;
        return new RecResult(sb.toString(), confidence);
    }

    public void release() {
        predictor = null;
    }
}
