package com.kkx.ppocrv3;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import org.autojs.plugin.sdk.Plugin;
import org.json.JSONArray;

import com.kkx.ppocrv3.ocr.OcrEngine;

import java.io.IOException;

/**
 * Auto.js Pro 插件入口（按教程继承 Plugin）。
 *
 * 暴露给 JS 胶水层的 public API：
 *   - String  version()
 *   - boolean init(boolean tiny)
 *   - String  ocr(android.graphics.Bitmap, double conf, String rangeJson, double scale)
 *   - String  ocrFile(String path, double conf, String rangeJson, double scale)
 *   - void    exit()
 *
 * API 与旧版（道无涯 PP-OCRv3 插件）兼容，可直接替换。
 */
public class PpOcrV3Plugin extends Plugin {

    private OcrEngine engine;
    private Boolean currentTiny;
    private Context selfContext;
    private String lastError;

    public PpOcrV3Plugin(Context context, Context selfContext,
                         Object runtime, Object topLevelScope) {
        super(context, selfContext, runtime, topLevelScope);
        this.selfContext = selfContext;
    }

    /** 返回 JS 胶水层(index.js)所在的 assets 子目录 */
    @Override
    public String getAssetsScriptDir() {
        return "PpOcrV3Entry";
    }

    /** 插件版本 */
    public String version() {
        return "1.0.0";
    }

    /** 初始化模型。tiny=true 使用 PP-OCRv3-tiny 量化模型 */
    public boolean init(boolean tiny) {
        lastError = null;
        try {
            if (engine == null || currentTiny == null || currentTiny != tiny) {
                if (engine != null) engine.release();
                engine = new OcrEngine(selfContext);
                engine.init(tiny);
                currentTiny = tiny;
            }
            return true;
        } catch (Throwable e) {
            lastError = Log.getStackTraceString(e);
            e.printStackTrace();
            return false;
        }
    }

    /** 识别 Bitmap（来自 images.read(...).bitmap 或 screenshot.bitmap） */
    public String ocr(Bitmap bitmap, double conf, String rangeJson, double scale) {
        try {
            int[] range = parseRange(rangeJson);
            return engine.recognize(bitmap, (float) conf, range, (float) scale);
        } catch (Exception e) {
            lastError = Log.getStackTraceString(e);
            e.printStackTrace();
            return "[]";
        }
    }

    /** 识别本地图片文件 */
    public String ocrFile(String path, double conf, String rangeJson, double scale) {
        Bitmap bmp = BitmapFactory.decodeFile(path);
        if (bmp == null) return "[]";
        try {
            return ocr(bmp, conf, rangeJson, scale);
        } finally {
            bmp.recycle();
        }
    }

    /** 返回上次 init 失败的原因（排错用） */
    public String lastError() {
        return lastError == null ? "" : lastError;
    }

    /** 释放模型、回收内存（脚本退出时调用） */
    public void exit() {
        if (engine != null) {
            engine.release();
            engine = null;
            currentTiny = null;
        }
    }

    private int[] parseRange(String rangeJson) {
        if (rangeJson == null || rangeJson.trim().isEmpty()) return null;
        try {
            JSONArray a = new JSONArray(rangeJson);
            if (a.length() >= 4) {
                return new int[]{a.getInt(0), a.getInt(1), a.getInt(2), a.getInt(3)};
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
