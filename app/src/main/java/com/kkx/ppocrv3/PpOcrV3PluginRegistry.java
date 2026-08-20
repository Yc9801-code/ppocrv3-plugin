package com.kkx.ppocrv3;

import android.content.Context;

import org.autojs.plugin.sdk.Plugin;
import org.autojs.plugin.sdk.PluginLoader;
import org.autojs.plugin.sdk.PluginRegistry;

/**
 * 插件注册器（按教程继承 PluginRegistry），注册默认插件。
 */
public class PpOcrV3PluginRegistry extends PluginRegistry {
    static {
        registerDefaultPlugin(new PluginLoader() {
            @Override
            public Plugin load(Context context, Context selfContext,
                               Object runtime, Object topLevelScope) {
                return new PpOcrV3Plugin(context, selfContext, runtime, topLevelScope);
            }
        });
    }
}
