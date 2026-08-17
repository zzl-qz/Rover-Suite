package com.rover.gateway.core.filter;

import com.rover.common.plugin.PluginJarScanner;
import com.rover.common.plugin.PluginSpiLoader;
import com.rover.common.plugin.PluginSpiLoader.PluginLoadResult;
import com.rover.common.spi.filter.Filter;
import java.net.URLClassLoader;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * Author: Daylight
 * Created: 2026-08-08 09:18:00
 * Description: 从 plugins 目录加载用户扩展 jar，并通过 SPI 发现 Filter
 */
@Slf4j
public class PluginFilterLoader {

    /** 插件 ClassLoader，供后续按类名实例化配置里的 Filter；热更新时会替换并 close 旧的。 */
    private volatile URLClassLoader pluginClassLoader;

    /**
     * 扫 plugins 目录下的 jar，靠 SPI 找 Filter。
     * jar 里要有 META-INF/services/com.rover.common.spi.filter.Filter
     */
    public List<Filter> loadFromDirectory(String pluginDir) {
        PluginLoadResult<Filter> result = PluginSpiLoader.load(Filter.class, pluginDir);
        if (result.directory() == null) {
            close();
            log.info("Filter plugin directory not found, skip external filters: {}",
                    PluginSpiLoader.resolveDirectory(pluginDir).toAbsolutePath());
            return List.of();
        }
        if (result.jarCount() == 0) {
            close();
            log.info("No filter plugin jars found in {}", result.directory().toAbsolutePath());
            return List.of();
        }

        // 先挂上新 loader，再关旧的，避免热更窗口里 createFilter 落到已关闭的 loader
        URLClassLoader previous = pluginClassLoader;
        pluginClassLoader = result.classLoader();
        PluginJarScanner.closeQuietly(previous);

        log.info("Loaded {} filter plugin jar(s) from {}",
                result.jarCount(), result.directory().toAbsolutePath());
        for (Filter filter : result.instances()) {
            log.info(
                    "Discovered plugin filter via SPI: name={}, order={}, class={}",
                    filter.getName(),
                    filter.getOrder(),
                    filter.getClass().getName());
        }
        return result.instances();
    }

    /** 按全限定类名实例化 Filter，优先用已加载的插件 ClassLoader。 */
    public Filter createFilter(String className) {
        try {
            return PluginSpiLoader.newInstance(Filter.class, className, pluginClassLoader);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(ex.getMessage(), ex);
        }
    }

    /** 关闭当前持有的插件 ClassLoader（进程退出或彻底禁用插件时调用）。 */
    public void close() {
        URLClassLoader previous = pluginClassLoader;
        pluginClassLoader = null;
        PluginJarScanner.closeQuietly(previous);
    }
}
