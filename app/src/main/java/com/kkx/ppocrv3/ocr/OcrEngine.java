package com.kkx.ppocrv3.ocr;

import android.content.Context;
import android.graphics.Bitmap;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * OCR 引擎：串联 DB 检测 + CRNN 识别，管理 Paddle Lite 生命周期。
 *
 * 相对旧插件（基于 EasyEdge / 127.0.0.1 本地 HTTP 服务）的核心改进：
 *  1) 进程内直接推理，不依赖后台服务，杜绝「后台被系统杀死后失效」。
 *  2) 初始化时设置 KMP_DUPLICATE_LIB_OK，规避 Paddle Lite 与宿主 OpenMP 冲突。
 *  3) 模型随插件 assets 下发，无需联网。
 */
public final class OcrEngine {

    private final Context selfContext;
    private DbDetector detector;
    private CrnnRecognizer recognizer;
    private Vocab vocab;
    private boolean ready = false;

    public OcrEngine(Context selfContext) {
        this.selfContext = selfContext;
    }

    /** 设置 OpenMP 重复库兼容环境变量（解决 OMP 冲突） */
    private static void fixOpenMp() {
        try {
            // Android 5+ 通过 android.system.Os.setenv 设置
            Class<?> os = Class.forName("android.system.Os");
            java.lang.reflect.Method setenv = os.getMethod("setenv",
                    String.class, String.class, int.class);
            setenv.invoke(null, "KMP_DUPLICATE_LIB_OK", "TRUE", 1);
        } catch (Throwable ignored) {
            // API 低版本或已加载时静默失败，不影响主流程
        }
    }

    /** 加载模型并初始化推理器。tiny=true 使用 PP-OCRv3-tiny 量化模型。 */
    public synchronized void init(boolean tiny) throws IOException {
        fixOpenMp();
        File cache = new File(selfContext.getCacheDir(), "ppocrv3");
        if (!cache.exists()) cache.mkdirs();

        String detAsset = tiny ? "ppocrv3/det_tiny.nb" : "ppocrv3/det.nb";
        String recAsset = tiny ? "ppocrv3/rec_tiny.nb" : "ppocrv3/rec.nb";
        String detPath = copyAsset(detAsset, cache);
        String recPath = copyAsset(recAsset, cache);
        String keysPath = copyAsset("ppocrv3/ppocr_keys_v1.txt", cache);

        vocab = new Vocab(keysPath);
        detector = new DbDetector();
        detector.init(detPath);
        recognizer = new CrnnRecognizer(vocab);
        recognizer.init(recPath);
        ready = true;
    }

    public boolean isReady() {
        return ready;
    }

    /**
     * 识别整张图 / 指定区域。
     *
     * @param bitmap   原图 Bitmap
     * @param conf     置信度阈值（0~1）
     * @param range    相对原图的范围 [x1,y1,x2,y2]，null 或长度不足则全图
     * @param scale    放大倍率（>1 时对裁剪区域先放大再识别）
     * @return JSON 字符串：[{text, region:[l,t,r,b], center:[cx,cy], confidence}]
     */
    public synchronized String recognize(Bitmap bitmap, float conf,
                                          int[] range, float scale) throws IOException, JSONException {
        if (!ready) throw new IllegalStateException("请先调用 init() 初始化模型");

        boolean derived = false;
        Bitmap work = bitmap;
        int ox = 0, oy = 0; // 原图偏移（来自 range）
        if (range != null && range.length >= 4
                && (range[2] > range[0]) && (range[3] > range[1])) {
            work = ImageUtils.crop(bitmap, range[0], range[1],
                    range[2] - range[0], range[3] - range[1]);
            derived = true;
            ox = range[0];
            oy = range[1];
        }
        if (scale > 1.0f) {
            Bitmap scaled = ImageUtils.scale(work, scale);
            if (derived) work.recycle();
            work = scaled;
            derived = true;
        }

        try {
            java.util.List<DbDetector.Detection> dets = detector.detect(work);
            JSONArray arr = new JSONArray();
            for (DbDetector.Detection d : dets) {
                int[] box = ImageUtils.axisAlignedBox(d.points);
                Bitmap textBmp = ImageUtils.crop(work, box[0], box[1],
                        box[2] - box[0], box[3] - box[1]);
                CrnnRecognizer.RecResult rec = recognizer.recognize(textBmp);
                textBmp.recycle();

                if (rec.text.isEmpty() || rec.confidence < conf) continue;

                // 把检测点从 work 坐标映射回原图坐标
                float[][] origPts = new float[4][];
                for (int i = 0; i < 4; i++) {
                    float x = d.points[i][0] / (scale > 1 ? scale : 1) + ox;
                    float y = d.points[i][1] / (scale > 1 ? scale : 1) + oy;
                    origPts[i] = new float[]{x, y};
                }
                int[] region = ImageUtils.axisAlignedBox(origPts);
                int cx = (region[0] + region[2]) / 2;
                int cy = (region[1] + region[3]) / 2;

                JSONObject obj = new JSONObject();
                obj.put("text", rec.text);
                JSONArray regionArr = new JSONArray();
                regionArr.put(region[0]).put(region[1]).put(region[2]).put(region[3]);
                obj.put("region", regionArr);
                JSONArray centerArr = new JSONArray();
                centerArr.put(cx).put(cy);
                obj.put("center", centerArr);
                obj.put("confidence", round2(rec.confidence));
                arr.put(obj);
            }
            return arr.toString();
        } finally {
            if (derived && work != bitmap) work.recycle();
        }
    }

    public void release() {
        if (detector != null) detector.release();
        if (recognizer != null) recognizer.release();
        detector = null;
        recognizer = null;
        vocab = null;
        ready = false;
    }

    private String copyAsset(String assetName, File outDir) throws IOException {
        InputStream is = null;
        FileOutputStream fos = null;
        try {
            is = selfContext.getAssets().open(assetName);
            File out = new File(outDir, assetName.replace("/", "_"));
            fos = new FileOutputStream(out);
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) fos.write(buf, 0, n);
            return out.getAbsolutePath();
        } finally {
            if (is != null) is.close();
            if (fos != null) fos.close();
        }
    }

    private static float round2(float v) {
        return Math.round(v * 100) / 100f;
    }
}
