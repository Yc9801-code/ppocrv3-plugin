package com.kkx.ppocrv3.ocr;

import android.graphics.Bitmap;

import com.baidu.paddle.lite.MobileConfig;
import com.baidu.paddle.lite.PaddlePredictor;
import com.baidu.paddle.lite.PowerMode;
import com.baidu.paddle.lite.Tensor;

import java.util.ArrayList;
import java.util.List;

/**
 * DB 文本检测（PP-OCRv3 det 模型）。
 * 预处理：等比缩放到 maxSideLen 以内，补边到 32 的倍数，BGR + ImageNet 归一化。
 * 后处理：概率图阈值化 -> 连通域 -> 协方差旋转框 -> DB 外扩(unclip) -> 盒得分过滤。
 * 不依赖 OpenCV，避免再引入一套 native 库造成冲突。
 */
public final class DbDetector {

    // 检测归一化（与 PaddleOCR 训练一致）：BGR 顺序
    private static final float[] MEAN = {0.485f, 0.456f, 0.406f};
    private static final float[] STD = {0.229f, 0.224f, 0.225f};

    private final int maxSideLen;
    private final float thresh;        // 概率阈值（DBPostProcess 默认 0.3）
    private final float boxThresh;     // 盒得分阈值（默认 0.6）
    private final float unclipRatio;   // 外扩比例（默认 1.5）
    private final int minArea;         // 最小盒面积，过滤噪点

    private PaddlePredictor predictor;

    public DbDetector() {
        this(960, 0.3f, 0.6f, 1.5f, 9);
    }

    public DbDetector(int maxSideLen, float thresh, float boxThresh,
                      float unclipRatio, int minArea) {
        this.maxSideLen = maxSideLen;
        this.thresh = thresh;
        this.boxThresh = boxThresh;
        this.unclipRatio = unclipRatio;
        this.minArea = minArea;
    }

    public void init(String modelPath) {
        MobileConfig config = new MobileConfig();
        config.setModelFromFile(modelPath);
        config.setPowerMode(PowerMode.LITE_POWER_HIGH);
        config.setThreads(4);
        predictor = PaddlePredictor.createPaddlePredictor(config);
    }

    /** 一次检测结果：4 点多边形(原图坐标) + 盒得分 */
    public static class Detection {
        public final float[][] points; // 4x2，[left-top, right-top, right-bottom, left-bottom]
        public final float score;

        public Detection(float[][] points, float score) {
            this.points = points;
            this.score = score;
        }
    }

    public List<Detection> detect(Bitmap bmp) {
        int ow = bmp.getWidth();
        int oh = bmp.getHeight();

        // 1) 等比缩放
        int rw, rh;
        if (Math.max(ow, oh) > maxSideLen) {
            double s = maxSideLen / (double) Math.max(ow, oh);
            rw = (int) Math.round(ow * s);
            rh = (int) Math.round(oh * s);
        } else {
            rw = ow;
            rh = oh;
        }
        rw = Math.max(rw, 32);
        rh = Math.max(rh, 32);

        // 2) 补边到 32 的倍数（右侧/下侧补黑）
        int padW = ((rw + 31) / 32) * 32;
        int padH = ((rh + 31) / 32) * 32;
        Bitmap scaled = Bitmap.createScaledBitmap(bmp, rw, rh, true);
        Bitmap canvas = Bitmap.createBitmap(padW, padH, Bitmap.Config.ARGB_8888);
        new android.graphics.Canvas(canvas).drawBitmap(scaled, 0, 0, null);
        if (scaled != bmp) scaled.recycle();

        // 3) 归一化到 CHW
        float[] chw = ImageUtils.toFloatCHW(canvas, padW, padH, MEAN, STD, 1.0f / 255.0f);
        if (canvas != bmp) canvas.recycle();

        // 4) 推理
        Tensor input = predictor.getInput(0);
        input.resize(new long[]{1, 3, padH, padW});
        input.setData(chw);
        predictor.run();
        Tensor out = predictor.getOutput(0);
        float[] prob = out.getFloatData();
        long[] shape = out.shape(); // 末尾两维为 [outH, outW]（可能为 4 维 [1,1,H,W]）
        int outH = (int) shape[shape.length - 2];
        int outW = (int) shape[shape.length - 1];

        // 5) 后处理：坐标从 out 空间映射回原图
        float factorX = (padW / (float) outW) * (ow / (float) rw);
        float factorY = (padH / (float) outH) * (oh / (float) rh);
        return postprocess(prob, outW, outH, factorX, factorY);
    }

    private List<Detection> postprocess(float[] prob, int w, int h,
                                         float factorX, float factorY) {
        // 阈值化二值图
        boolean[] bin = new boolean[w * h];
        for (int i = 0; i < bin.length; i++) bin[i] = prob[i] > thresh;

        // 8 连通域标记
        int[] label = new int[w * h];
        List<List<Integer>> comps = new ArrayList<>();
        int cur = 0;
        int[] dx = {-1, 0, 1, -1, 1, -1, 0, 1};
        int[] dy = {-1, -1, -1, 0, 0, 1, 1, 1};
        java.util.ArrayDeque<Integer> stack = new java.util.ArrayDeque<>();
        for (int sy = 0; sy < h; sy++) {
            for (int sx = 0; sx < w; sx++) {
                int idx = sy * w + sx;
                if (!bin[idx] || label[idx] != 0) continue;
                cur++;
                List<Integer> comp = new ArrayList<>();
                stack.push(idx);
                label[idx] = cur;
                while (!stack.isEmpty()) {
                    int p = stack.pop();
                    comp.add(p);
                    int px = p % w, py = p / w;
                    for (int k = 0; k < 8; k++) {
                        int nx = px + dx[k], ny = py + dy[k];
                        if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                        int ni = ny * w + nx;
                        if (bin[ni] && label[ni] == 0) {
                            label[ni] = cur;
                            stack.push(ni);
                        }
                    }
                }
                comps.add(comp);
            }
        }

        // 每个连通域 -> 旋转框 -> 外扩 -> 过滤
        List<Detection> result = new ArrayList<>();
        for (List<Integer> comp : comps) {
            if (comp.size() < minArea) continue;

            // 收集点 & 盒得分（连通域内概率均值）
            float sum = 0;
            float[] cx = new float[comp.size()];
            float[] cy = new float[comp.size()];
            for (int i = 0; i < comp.size(); i++) {
                int p = comp.get(i);
                cx[i] = p % w;
                cy[i] = p / w;
                sum += prob[p];
            }
            float score = sum / comp.size();

            // 协方差旋转框（原 out 空间）
            float[][] box = rotatedBox(cx, cy);

            // DB 外扩
            box = unclip(box, unclipRatio);

            // 映射回原图坐标
            for (float[] pt : box) {
                pt[0] *= factorX;
                pt[1] *= factorY;
            }

            // 外扩后面积过滤
            if (polygonArea(box) < minArea) continue;
            // 盒得分过滤
            if (score < boxThresh) continue;

            result.add(new Detection(box, score));
            if (result.size() >= 1000) break; // max_candidates
        }
        return result;
    }

    /** 用点集协方差的主轴方向构造贴合的旋转矩形（4 点） */
    private static float[][] rotatedBox(float[] x, float[] y) {
        int n = x.length;
        float mx = 0, my = 0;
        for (int i = 0; i < n; i++) { mx += x[i]; my += y[i]; }
        mx /= n; my /= n;
        float sxx = 0, syy = 0, sxy = 0;
        for (int i = 0; i < n; i++) {
            float dx = x[i] - mx, dy = y[i] - my;
            sxx += dx * dx; syy += dy * dy; sxy += dx * dy;
        }
        sxx /= n; syy /= n; sxy /= n;
        // 2x2 对称矩阵特征值/向量（闭式解）
        float tr = sxx + syy;
        float det = sxx * syy - sxy * sxy;
        float disc = (float) Math.sqrt(Math.max(0, tr * tr / 4 - det));
        float l1 = tr / 2 + disc;
        float e1x, e1y;
        if (Math.abs(sxy) > 1e-6) {
            e1x = l1 - syy;
            e1y = sxy;
        } else {
            e1x = (sxx >= syy) ? 1 : 0;
            e1y = (sxx >= syy) ? 0 : 1;
        }
        float len = (float) Math.hypot(e1x, e1y);
        e1x /= len; e1y /= len;
        float e2x = -e1y, e2y = e1x;

        // 在主轴坐标系下取包围盒
        float umin = Float.MAX_VALUE, umax = -Float.MAX_VALUE;
        float vmin = Float.MAX_VALUE, vmax = -Float.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            float dx = x[i] - mx, dy = y[i] - my;
            float u = dx * e1x + dy * e1y;
            float v = dx * e2x + dy * e2y;
            umin = Math.min(umin, u); umax = Math.max(umax, u);
            vmin = Math.min(vmin, v); vmax = Math.max(vmax, v);
        }
        // 4 角点：主轴空间 -> 原空间
        float[][] pts = new float[4][];
        float[][] uv = {{umin, vmin}, {umax, vmin}, {umax, vmax}, {umin, vmax}};
        for (int i = 0; i < 4; i++) {
            pts[i] = new float[]{
                    mx + uv[i][0] * e1x + uv[i][1] * e2x,
                    my + uv[i][0] * e1y + uv[i][1] * e2y
            };
        }
        return pts;
    }

    /** DB 外扩：每条边沿外法线方向偏移 d = area*ratio/perimeter */
    private static float[][] unclip(float[][] poly, float ratio) {
        int n = poly.length;
        float area = Math.abs(polygonArea(poly));
        float perim = 0;
        for (int i = 0; i < n; i++) {
            float[] a = poly[i], b = poly[(i + 1) % n];
            perim += (float) Math.hypot(b[0] - a[0], b[1] - a[1]);
        }
        if (perim < 1e-6) return poly;
        float d = area * ratio / perim;

        // 质心
        float cx = 0, cy = 0;
        for (float[] p : poly) { cx += p[0]; cy += p[1]; }
        cx /= n; cy /= n;

        // 每条边的外法线
        float[][] normal = new float[n][];
        for (int i = 0; i < n; i++) {
            float[] a = poly[i], b = poly[(i + 1) % n];
            float ex = b[0] - a[0], ey = b[1] - a[1];
            float el = (float) Math.hypot(ex, ey);
            ex /= el; ey /= el;
            float nx = -ey, ny = ex; // 左法线
            if (nx * (a[0] - cx) + ny * (a[1] - cy) < 0) { nx = -nx; ny = -ny; }
            normal[i] = new float[]{nx, ny};
        }
        // 偏移后的边
        float[][][] edges = new float[n][2][];
        for (int i = 0; i < n; i++) {
            float[] a = poly[i], b = poly[(i + 1) % n];
            edges[i][0] = new float[]{a[0] + normal[i][0] * d, a[1] + normal[i][1] * d};
            edges[i][1] = new float[]{b[0] + normal[i][0] * d, b[1] + normal[i][1] * d};
        }
        // 相邻偏移边求交，得到新顶点
        float[][] out = new float[n][];
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            out[j] = intersect(edges[i][0], edges[i][1], edges[j][0], edges[j][1]);
        }
        return out;
    }

    private static float[] intersect(float[] p1, float[] p2, float[] p3, float[] p4) {
        float x1 = p1[0], y1 = p1[1], x2 = p2[0], y2 = p2[1];
        float x3 = p3[0], y3 = p3[1], x4 = p4[0], y4 = p4[1];
        float den = (x1 - x2) * (y3 - y4) - (y1 - y2) * (x3 - x4);
        if (Math.abs(den) < 1e-9) return new float[]{(x1 + x3) / 2, (y1 + y3) / 2};
        float t = ((x1 - x3) * (y3 - y4) - (y1 - y3) * (x3 - x4)) / den;
        return new float[]{x1 + t * (x2 - x1), y1 + t * (y2 - y1)};
    }

    private static float polygonArea(float[][] poly) {
        float area = 0;
        int n = poly.length;
        for (int i = 0; i < n; i++) {
            float[] a = poly[i], b = poly[(i + 1) % n];
            area += a[0] * b[1] - b[0] * a[1];
        }
        return area / 2;
    }

    public void release() {
        if (predictor != null) {
            // Paddle Lite 当前版本通过 GC 释放；置空便于回收
            predictor = null;
        }
    }
}
