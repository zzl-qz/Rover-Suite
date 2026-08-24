package com.rover.gateway.core.filter;

import com.rover.common.plugin.PluginSpiLoader;
import com.rover.gateway.core.filter.ratelimit.RateLimitSettings;
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

    /** 内置本地限流配置，默认关闭。 */
    private final RateLimitSettings rateLimit = new RateLimitSettings();

    /**
     * 是否装配访问日志过滤器。默认 true：打 debug（默认 INFO 级别下不刷屏）；
     * 设为 false 时不装 AccessLogFilter，连 debug 也不走。
     */
    private volatile boolean accessLog = true;

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

    public RateLimitSettings getRateLimit() {
        return rateLimit;
    }

    public boolean isAccessLog() {
        return accessLog;
    }

    public void setAccessLog(boolean accessLog) {
        this.accessLog = accessLog;
    }
}
