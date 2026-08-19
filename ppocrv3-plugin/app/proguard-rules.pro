# Auto.js Pro 插件混淆规则
# 默认 release 不开启混淆（app/build.gradle minifyEnabled=false）。
# 如需混淆，至少保留以下内容，否则插件入口/原生方法会被干掉导致加载失败：

# 保留插件入口类与注册类（包名按实际情况修改）
-keep class com.kkx.ppocrv3.PpOcrV3Plugin { *; }
-keep class com.kkx.ppocrv3.PpOcrV3PluginRegistry { *; }
-keep class com.kkx.ppocrv3.ocr.** { *; }

# 保留 Paddle Lite JNI 类与 native 方法
-keep class com.baidu.paddle.lite.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# 保留 index.js 胶水层引用的反射入口
-keepclassmembers class * {
    public <methods>;
}
