/*
 * ppocrv3 插件 · Auto.js Pro 调用示例
 * ------------------------------------------------------------
 * 前置：先把本工程编译生成的 app-release.apk 安装到手机（无需打开，
 *       它会被 Auto.js Pro 自动识别为插件）。
 *
 * 调用约定：
 *   $plugins.load("com.kkx.ppocrv3")  -> 返回 ppocrv3 对象
 *   ppocrv3.init(tiny)            tiny=false 标准版 / true 量化 tiny 版
 *   ppocrv3.ocr(bitmap, conf, range, scale)
 *        bitmap : images.read(path).bitmap 或 screenshot().bitmap
 *        conf   : 置信度阈值 0~1（默认 0.3）
 *        range  : [x1,y1,x2,y2] 相对原图，省略=全图
 *        scale  : 放大倍率(>1)，省略=1
 *   ppocrv3.ocrFile(path, conf, range, scale)  直接识别本地图片文件
 *   ppocrv3.version()
 *   ppocrv3.exit()               脚本退出务必调用，释放模型与内存
 *
 * 注意：ocr/ocrFile 返回的是 JSON 字符串，需要用 JSON.parse 解析。
 * 返回结构：[{text, region:[l,t,r,b], center:[cx,cy], confidence}, ...]
 */

// 1) 加载插件（包名与 build.gradle 的 namespace 保持一致）
var ppOCR = $plugins.load("com.kkx.ppocrv3");
if (!ppOCR) {
    toastLog("插件未安装，请先安装 app-release.apk");
    exit();
}
console.log("插件版本:", ppOCR.version());

// 2) 初始化模型（标准版）。换量化版：ppOCR.init(true)
if (!ppOCR.init(false)) {
    toastLog("模型初始化失败，请确认 assets/ppocrv3 已含 det.nb/rec.nb/字典");
    exit();
}

// ---------- 示例 A：识别本地图片文件（最常用）----------
var path = "/sdcard/daowuya.png";          // 换成你的图片路径
var result = JSON.parse(ppOCR.ocrFile(path, 0.3));
console.log("识别到 " + result.length + " 个文本块：");
result.forEach(function (item) {
    console.log(
        "文本=" + item.text +
        "  坐标=" + JSON.stringify(item.region) +
        "  中心=" + JSON.stringify(item.center) +
        "  置信度=" + item.confidence
    );
});

// ---------- 示例 B：识别屏幕截图，并只取图片中间一块区域 ----------
// var img = screenshot();
// // 取屏幕中心 600x400 的区域：[x1,y1,x2,y2]
// var w = device.width, h = device.height;
// var range = [ (w-600)/2, (h-400)/2, (w+600)/2, (h+400)/2 ];
// var r2 = JSON.parse(ppOCR.ocr(img.bitmap, 0.3, range));
// img.recycle();

// ---------- 示例 C：识别后点击某个文本（RPA 常见用法）----------
// var target = result.find(function (it) { return it.text.indexOf("确定") >= 0; });
// if (target) {
//     var c = target.center;
//     click(c[0], c[1]);      // 或 Tap(c[0], c[1])
// }

// 3) 脚本结束释放资源
events.on("exit", function () {
    ppOCR.exit();
});
