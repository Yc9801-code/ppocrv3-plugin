# ppocrv3 —— Auto.js Pro 文字识别插件（PP-OCRv3 重新封装）

基于 [道无涯 Auto.js Pro 插件开发流程](https://www.daowuya.love/autojspro%e6%8f%92%e4%bb%b6%e5%bc%80%e5%8f%91%e6%b5%81%e7%a8%8b/) 重新封装的
**进程内、设备端** PP-OCRv3 文字识别插件，用 Paddle Lite 直接做检测(DB) + 识别(CRNN/SVTR)，
对外 API 与旧版 `com.daowuya.ppocrv3` 兼容，可直接替换。

---

## 一、旧插件的问题 & 本插件的修复

| 问题 | 旧插件表现 | 本插件如何修复 |
|------|-----------|---------------|
| **架构不匹配** | 检测/识别拆成 `armeabi-v7a` 与 `arm64-v8a` 两个独立 APK；宿主进程位宽(32/64)与插件 `.so` 不一致即 `UnsatisfiedLinkError: ... is 32-bit instead of 64-bit` / `NoClassDefFoundError` | 单个 APK 同时内置两套 native 库（`app/build.gradle` 的 `ndk.abiFilters` 包含两者），系统自动抽取匹配宿主位宽的 `.so`，不再需要挑包 |
| **OpenMP 冲突** | Paddle Lite 内部用 OpenMP，与宿主的 OpenMP 冲突导致崩溃/卡死 | 初始化时通过 `android.system.Os.setenv("KMP_DUPLICATE_LIB_OK","TRUE",1)` 设置兼容环境变量（`OcrEngine.fixOpenMp()`），即教程提到的 OMP 解法 |
| **后台服务脆弱** | v4 起把推理做成 `127.0.0.1:1919` 本地 HTTP 服务；后台被系统杀掉就失效，需「省电策略→无限制」保活 | 进程内直接推理，无后台服务、无网络依赖，装完即用，识别更稳更快 |
| **依赖云端/外部** | EasyEdge 推理、非 Pro 平台走 HTTP 对接 | 模型随插件 assets 下发，纯本地推理 |

---

## 二、工程结构

```
ppocrv3-plugin/
├── settings.gradle / build.gradle / gradle.properties
├── scripts/
│   ├── download_deps.sh      # 拉取 Paddle Lite 预测库(jar+so) → app/libs、app/src/main/jniLibs
│   └── download_models.sh    # 拉取 PP-OCRv3 模型+字典并转 .nb → app/src/main/assets/ppocrv3
└── app/
    ├── build.gradle          # Plugin SDK 依赖 + 双架构 abiFilters
    ├── proguard-rules.pro
    ├── src/main/
    │   ├── AndroidManifest.xml   # 注册 org.autojs.plugin.sdk.registry
    │   ├── assets/PpOcrV3Entry/index.js   # JS 胶水层
    │   ├── assets/ppocrv3/             # det.nb rec.nb det_tiny.nb rec_tiny.nb ppocr_keys_v1.txt
    │   ├── jniLibs/<abi>/libpaddle_lite_jni.so
    │   └── java/com/kkx/ppocrv3/
    │       ├── PpOcrV3Plugin.java          # 插件入口(extends Plugin)，暴露 public API
    │       ├── PpOcrV3PluginRegistry.java  # 注册器(extends PluginRegistry)
    │       └── ocr/
    │           ├── OcrEngine.java          # 编排：拷贝 assets、OMP 环境、串联检测+识别
    │           ├── DbDetector.java         # DB 检测(预处理+后处理，连通域+旋转框，无 OpenCV)
    │           ├── CrnnRecognizer.java     # CRNN/SVTR 识别(预处理+CTC 贪婪解码)
    │           ├── Vocab.java              # 词表加载
    │           └── ImageUtils.java         # Bitmap↔float(CHW)、裁剪、缩放
```

---

## 三、构建步骤

前置：安装好 **Android SDK（compileSdk 34）即可，无需 NDK**（本插件不编译 native 代码，
`.so` 由脚本直接下载后打入 `jniLibs`）。

```bash
# 0)（首次）生成 Gradle wrapper（已生成可跳过）
gradle wrapper --gradle-version 8.2

# 1) 拉取 Paddle Lite 预测库（jar + 两套架构 so）
bash scripts/download_deps.sh

# 2) 下载 PP-OCRv3 模型与字典，并转成 .nb
#    前置：pip install paddlelite   （提供 paddle_lite_opt）
bash scripts/download_models.sh

# 3) 在 local.properties 里指向你的 Android SDK（或用环境变量 ANDROID_HOME）
echo "sdk.dir=/你的/sdk/路径" > local.properties

# 4) 用 Android Studio 打开本工程，Build → Build Bundles / APK(s) → Build APK(s)
#    产物：app/build/outputs/apk/release/app-release.apk
#    想直接装到手机：用 apksigner 自签一个 keystore 后安装即可（见 .github/workflows 示例）
```

> 不想装 Android Studio？见 **第九节**：推到 GitHub 后由 Actions 云端自动出已签名的
> `ppocrv3-plugin.apk`，下载即装。

> 模型来源：检测/识别用 PaddleOCR 官方 `ch_PP-OCRv3_det_infer` / `ch_PP-OCRv3_rec_infer`，
> tiny 用对应的 `_slim_infer` 量化版；字典 `ppocr_keys_v1.txt` 来自 PaddleOCR 仓库。
> `.nb` 由 `paddle_lite_opt`（`--valid_targets arm --optimize_out_type naive_buffer`）生成。

---

## 四、在 Auto.js Pro 中使用

> `ocr` / `ocrFile` 返回的是 **JSON 字符串**，要按字段取用需先 `JSON.parse`。
> 完整可运行示例见工程根目录 `EXAMPLE.js`。

```javascript
// 加载插件（只需一次）。包名按你实际修改的包名调整
var ppOCR = $plugins.load("com.kkx.ppocrv3");

// 初始化，加载模型（只需一次）。tiny=true 使用 PP-OCRv3-tiny 量化模型
ppOCR.init(false);

// 1) 读取本地图片识别
var img = images.read("/sdcard/daowuya.png");
var result = JSON.parse(ppOCR.ocr(img.bitmap, 0.3));   // 省略 range/scale=全图
result.forEach(function (it) { console.log(it.text, it.region, it.center, it.confidence); });
img.recycle();

// 2) 实时截图识别（坐标相对于原图）
var cap = screenshot();
var result2 = JSON.parse(ppOCR.ocr(cap.bitmap, 0.3, [100, 200, 800, 1000]));
console.log(result2);
cap.recycle();

// 3) 识别本地文件（一行版）
var r3 = JSON.parse(ppOCR.ocrFile("/sdcard/daowuya.png", 0.3));
console.log(r3);

// 脚本退出或不再识别时释放模型
events.on("exit", function () { ppOCR.exit(); });
```

### API

| 方法 | 说明 |
|------|------|
| `init(tiny)` | 初始化模型。`tiny=true` 用 PP-OCRv3-tiny（更快、略损精度）。返回 `true/false` |
| `ocr(bitmap, conf, range, scale)` | 识别 Bitmap。`conf` 置信度阈值；`range=[x1,y1,x2,y2]` 可省略（全图）；`scale` 放大倍率可省略(默认1) |
| `ocrFile(path, conf, range, scale)` | 识别本地图片文件 |
| `version()` | 返回插件版本 |
| `exit()` | 卸载模型、释放内存 |

### 返回格式（JSON 数组，与旧插件一致）

```json
[
  { "text": "道无涯", "region": [173, 666, 302, 1133], "center": [238, 900], "confidence": 0.91 },
  { "text": "插件",   "region": [298, 674, 1161, 1076], "center": [730, 875], "confidence": 0.94 }
]
```

- `region`：文本轴对齐包围盒 `[left, top, right, bottom]`，**坐标相对于传入的原图**
- `center`：包围盒中心 `[cx, cy]`
- `confidence`：识别置信度（0~1）

---

## 五、改成你自己的包名

把 `com.kkx.ppocrv3` 整体改掉，共 3 处必须同步：

1. `app/build.gradle` 的 `namespace`
2. `app/src/main/AndroidManifest.xml` 里 `android:value="...PpOcrV3PluginRegistry"`
3. Java 源码包名 `package` 声明与目录结构
4. 调用处 `$plugins.load("你的包名")`

---

## 六、关键调参点（如识别不准）

- **检测阈值**：`DbDetector` 构造参数 `thresh=0.3`（概率阈值）、`boxThresh=0.6`（盒得分阈值）、`unclipRatio=1.5`（文本外扩）、`maxSideLen=960`（最长边）。
- **置信度过滤**：调用端 `conf` 提高可去噪（如 0.5）。
- **预处理常量**：检测用 ImageNet 均值/方差，识别用 `0.5/0.5`（见 `DbDetector.MEAN/STD`、`CrnnRecognizer.MEAN/STD`），与 PaddleOCR 训练一致。
- **通道顺序**：统一 BGR（与 cv2 训练一致），已在 `ImageUtils.toFloatCHW` 处理。

---

## 七、混淆与加固建议（来自教程）

- 插件功能函数名可混淆/改名，只暴露 `index.js` 入口。
- **不要**用 360 加固（加固后接口无法暴露）；**不要** dcc（会报错）。
- 推荐：jsv6/v7 混淆 `index.js` + np 管理器控制流混淆 + APKVM 保护。
- `app/proguard-rules.pro` 已保留插件入口、注册类与 Paddle Lite native 方法。

---

## 八、已知限制

- 识别预处理对裁剪框做轴对齐截取（未做透视矫正），重度倾斜文本可能影响精度；如需更高精度可改为四点透视变换裁剪。
- DB 后处理用协方差旋转框近似 `minAreaRect` + 多边形外扩近似 Vatti clipping，对绝大多数横排/竖排文本足够；极端场景可接入 OpenCV 的 `findContours`/`minAreaRect`。
- Paddle Lite Java 库未上 Maven Central，采用 `libs/PaddlePredictor.jar` + `jniLibs` 手动放置（见 `download_deps.sh`）。

---

## 九、不想本地装 Android Studio？云端一键出 APK / 发版

工程内置两个 GitHub Actions 工作流，都跑在云端（自动装 JDK17 + Android SDK、转模型、编译、签名）：

| 工作流 | 文件 | 触发 | 产物 |
|--------|------|------|------|
| 开发构建 | `.github/workflows/build-apk.yml` | **手动** Run workflow | Artifacts 里的 `ppocrv3-plugin.apk` |
| **发版** | `.github/workflows/release.yml` | 打 `v*` tag 推送 / 手动填版本 | **GitHub Release**（含可下载 APK + SHA256） |

### 9.1 开发构建（手动出包）
1. 工程推到 GitHub 仓库（含 `gradlew`、`.github/workflows` 等）。
2. 仓库 **Actions → Build ppocrv3 Plugin APK → Run workflow**。
3. 完成后到 **Artifacts** 下载 `ppocrv3-plugin-apk`，里面是已签名的 `ppocrv3-plugin.apk`。

### 9.2 一键发版到 GitHub Release（推荐对外发布）
打一个 `v*` tag 推送即自动发版，APK 直接挂到 Release 资产里，带可点击下载链接与 SHA256 校验：

```bash
# 本地改完 → 提交 → 打 tag → 推送，云端自动出 Release
git add -A && git commit -m "release v1.0.0"
git tag v1.0.0 && git push && git push --tags
# 到仓库 Releases 页即可看到 "PP-OCRv3 插件 v1.0.0"，点 ppocrv3-plugin.apk 下载
```

也可在 **Actions → Release ppocrv3 Plugin APK → Run workflow** 里手填版本名 `1.0.0` 触发。

### 9.3 用仓库 Secrets 注入签名密钥（务必配置）
`release.yml` 强制要求 Secrets，否则直接报错退出；`build-apk.yml` 若未配 Secrets 会临时生成仅供自测的 keystore。

在 GitHub 仓库 **Settings → Secrets and variables → Actions → New repository secret** 配置 4 项：

| Secret 名 | 内容 | 生成方式 |
|-----------|------|----------|
| `KEYSTORE_BASE64` | 你的 `release.keystore` 的 base64 | `base64 -w0 release.keystore` |
| `KEYSTORE_PASSWORD` | keystore 密码 | 你自己设的 storepass |
| `KEY_ALIAS` | 密钥别名 | 如 `ppocrv3` |
| `KEY_PASSWORD` | 密钥密码 | 你自己设的 keypass |

生成并入库 keystore（一次即可，长期复用）：
```bash
keytool -genkeypair -v -keystore release.keystore \
  -alias ppocrv3 -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass 你的密码 -keypass 你的密码 \
  -dname "CN=ppocrv3,O=kkx,C=CN"
# 把 release.keystore 转 base64 填入 KEYSTORE_BASE64
base64 -w0 release.keystore
# 注意：release.keystore 本身不要提交到仓库（已在 .gitignore 忽略）
```

工作流里 Gradle 由 `gradle/actions/setup-gradle` 以 `gradle-version: 8.2` 驱动，因此仓库不强制提交 `gradlew`（已一并生成，方便本地 Android Studio 打开）。
