// ppocrv3 插件 JS 胶水层
// 由 Plugin.getAssetsScriptDir() 返回目录 PpOcrV3Entry 加载。
// 仅做参数适配与转发，所有推理在 Java 层完成（进程内，无需后台服务）。

module.exports = function (plugin) {
    function ppocrv3() {}

    // 初始化模型。tiny=true 使用 PP-OCRv3-tiny 量化模型
    ppocrv3.init = function (tiny) {
        return plugin.init(tiny === true);
    };

    // 识别 Bitmap
    //   bitmap: images.read(...).bitmap 或 screenshot.bitmap
    //   conf:   置信度阈值 0~1
    //   range:  [x1,y1,x2,y2]（相对原图），可省略表示全图
    //   scale:  放大倍率（>1 先放大再识别），可省略
    ppocrv3.ocr = function (bitmap, conf, range, scale) {
        var r = JSON.stringify(range === undefined ? [] : range);
        return plugin.ocr(bitmap, conf === undefined ? 0.3 : conf, r, scale === undefined ? 1 : scale);
    };

    // 识别本地图片文件
    ppocrv3.ocrFile = function (path, conf, range, scale) {
        var r = JSON.stringify(range === undefined ? [] : range);
        return plugin.ocrFile(path, conf === undefined ? 0.3 : conf, r, scale === undefined ? 1 : scale);
    };

    // 插件版本
    ppocrv3.version = function () {
        return plugin.version();
    };

    // 释放模型（脚本退出时务必调用）
    ppocrv3.exit = function () {
        plugin.exit();
    };

    return ppocrv3;
};
