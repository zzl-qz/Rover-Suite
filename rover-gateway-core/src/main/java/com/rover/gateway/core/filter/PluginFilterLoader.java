package com.rover.gateway.core.filter;

import com.rover.common.plugin.PluginSpiLoader;
import com.rover.common.plugin.PluginSpiLoader.PluginLoadResult;
import com.rover.common.spi.filter.Filter;
import java.net.URLClassLoader;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * Author: Daylight
 * Created: 2026-08-08 16:53:00
 * Description: 从 plugins 目录加载用户扩展 jar，并通过 SPI 发现 Filter
 *
 * 扫 jar / SPI / 反射骨架在 common.PluginSpiLoader；这里只做 Filter 侧日志与缓存 ClassLoader。
 */
@Slf4j
public class PluginFilterLoader {

    /** 插件 ClassLoader，供后续按类名实例化配置里的 Filter。 */
    private URLClassLoader pluginClassLoader;

    /**
     * 扫 plugins 目录下的 jar，靠 SPI 找 Filter。
     * jar 里要有 META-INF/services/com.rover.common.spi.filter.Filter
     */
    public List<Filter> loadFromDirectory(String pluginDir) {
        PluginLoadResult<Filter> result = PluginSpiLoader.load(Filter.class, pluginDir);
        if (result.directory() == null) {
            log.info("Filter plugin directory not found, skip external filters: {}",
                    PluginSpiLoader.resolveDirectory(pluginDir).toAbsolutePath());
            return List.of();
        }
        if (result.jarCount() == 0) {
            log.info("No filter plugin jars found in {}", result.directory().toAbsolutePath());
            return List.of();
        }

        pluginClassLoader = result.classLoader();
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

    /**
     * 按全限定类名实例化 Filter。
     * 优先用已加载的插件 ClassLoader。
     */
    public Filter createFilter(String className) {
        try {
            return PluginSpiLoader.newInstance(Filter.class, className, pluginClassLoader);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(ex.getMessage(), ex);
        }
    }
}
