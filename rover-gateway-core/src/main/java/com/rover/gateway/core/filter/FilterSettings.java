package com.rover.gateway.core.filter;

import com.rover.common.plugin.PluginSpiLoader;
import java.util.List;

/**
 * Author: Daylight
 * Created: 2026-08-08 11:44:00
 * Description: Gateway 过滤器加载配置：总开关、插件目录与按类名加载的过滤器
 */
public class FilterSettings {

    /** 是否加载外挂插件和配置中指定的过滤器，默认开启。 */
    private volatile boolean enabled = true;

    /** 用户扩展 jar 目录，默认是运行目录下的 plugins。 */
    private volatile String pluginDir = PluginSpiLoader.DEFAULT_DIR;

    /**
     * 额外按全限定类名加载的过滤器。
     * 例如：com.example.MyAuthFilter
     */
    private volatile List<String> classes = List.of();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getPluginDir() {
        return pluginDir;
    }

    public void setPluginDir(String pluginDir) {
        this.pluginDir = pluginDir == null || pluginDir.isBlank()
                ? PluginSpiLoader.DEFAULT_DIR
                : pluginDir.trim();
    }

    public List<String> getClasses() {
        return classes;
    }

    public void setClasses(List<String> classes) {
        this.classes = classes == null ? List.of() : List.copyOf(classes);
    }
}
