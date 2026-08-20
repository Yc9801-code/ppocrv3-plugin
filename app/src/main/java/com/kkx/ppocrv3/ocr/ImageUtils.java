package com.kkx.ppocrv3.ocr;

import android.graphics.Bitmap;
import android.graphics.Rect;

/**
 * 图像工具：Bitmap <-> float(CHW) 转换、裁剪、缩放。
 * 通道顺序统一为 BGR（与 PaddleOCR 用 cv2 读图训练一致），归一化用传入的 mean/std。
 */
public final class ImageUtils {

    private ImageUtils() {}

    /**
     * 把 Bitmap 缩放/填充到 (dstW, dstH)，并转成 CHW 的 float 数组（BGR 顺序）。
     *
     * @param mean 均值，长度 3，顺序 [meanB, meanG, meanR]
     * @param std  标准差，长度 3，顺序 [stdB, stdG, stdR]
     * @param scale 像素缩放系数，通常为 1/255
     */
    public static float[] toFloatCHW(Bitmap src, int dstW, int dstH,
                                      float[] mean, float[] std, float scale) {
        // 先缩放（保持宽高比由调用方决定；这里直接拉伸到目标尺寸）
        Bitmap bmp = Bitmap.createScaledBitmap(src, dstW, dstH, true);
        int[] pixels = new int[dstW * dstH];
        bmp.getPixels(pixels, 0, dstW, 0, 0, dstW, dstH);

        float[] data = new float[3 * dstW * dstH];
        int wh = dstW * dstH;
        for (int i = 0; i < wh; i++) {
            int p = pixels[i];
            float r = ((p >> 16) & 0xff) * scale;
            float g = ((p >> 8) & 0xff) * scale;
            float b = (p & 0xff) * scale;
            // BGR 三通道归一化
            float cb = (b - mean[0]) / std[0];
            float cg = (g - mean[1]) / std[1];
            float cr = (r - mean[2]) / std[2];
            int x = i % dstW;
            int y = i / dstW;
            data[0 * wh + y * dstW + x] = cb;
            data[1 * wh + y * dstW + x] = cg;
            data[2 * wh + y * dstW + x] = cr;
        }
        if (bmp != src) bmp.recycle();
        return data;
    }

    /** 区域裁剪（自动 clamp 到图内） */
    public static Bitmap crop(Bitmap src, int x, int y, int w, int h) {
        x = clamp(x, 0, src.getWidth() - 1);
        y = clamp(y, 0, src.getHeight() - 1);
        w = clamp(w, 1, src.getWidth() - x);
        h = clamp(h, 1, src.getHeight() - y);
        return Bitmap.createBitmap(src, x, y, w, h);
    }

    /** 按倍率缩放 */
    public static Bitmap scale(Bitmap src, float s) {
        if (s <= 1.0f) return src;
        int w = Math.round(src.getWidth() * s);
        int h = Math.round(src.getHeight() * s);
        return Bitmap.createScaledBitmap(src, w, h, true);
    }

    /** 求 4 点多边形的轴对齐包围盒 [left, top, right, bottom] */
    public static int[] axisAlignedBox(float[][] pts) {
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (float[] p : pts) {
            minX = Math.min(minX, p[0]);
            minY = Math.min(minY, p[1]);
            maxX = Math.max(maxX, p[0]);
            maxY = Math.max(maxY, p[1]);
        }
        return new int[]{(int) Math.round(minX), (int) Math.round(minY),
                (int) Math.round(maxX), (int) Math.round(maxY)};
    }

    /** 把 [left,top,right,bottom] 转成 4 点多边形 */
    public static float[][] boxToPoints(int[] box) {
        return new float[][]{
                {box[0], box[1]},
                {box[2], box[1]},
                {box[2], box[3]},
                {box[0], box[3]},
        };
    }

    public static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    public static Rect safeRect(int x, int y, int w, int h, int bw, int bh) {
        x = clamp(x, 0, bw);
        y = clamp(y, 0, bh);
        w = clamp(w, 1, bw - x);
        h = clamp(h, 1, bh - y);
        return new Rect(x, y, x + w, y + h);
    }
}
