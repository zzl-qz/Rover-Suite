package com.rover.gateway.core.filter;

import com.rover.common.spi.Filter;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import lombok.extern.slf4j.Slf4j;

/**
 * Author: Daylight
 * Created: 2026-08-08 16:53:00
 * Description: 从 plugins 目录加载用户扩展 jar，并通过 SPI 发现 Filter
 *
 * 这个类是什么：Gateway 插件 Filter 的 ClassLoader + SPI 加载器。
 * 核心职责：①扫描 plugins 目录下 jar 并建 URLClassLoader；②ServiceLoader 发现 Filter 实现；
 * ③按全限定类名反射实例化配置里指定的 Filter。
 * 被谁用：GatewayFilterAssembler 组装过滤器链时调用。
 */
@Slf4j
public class PluginFilterLoader {

    /**
     * 插件专用 ClassLoader。
     * 先查插件 jar，再委托给应用 ClassLoader，这样用户扩展可以依赖 Rover 的 Filter 接口。
     */
    private URLClassLoader pluginClassLoader;

    /**
     * 扫 plugins 目录下的 jar，靠 SPI 找 Filter。
     * jar 里要有 META-INF/services/com.rover.common.spi.Filter
     *
     * @param pluginDir 插件目录路径，null 或空时用默认 "plugins"
     * @return 去重后的 Filter 列表；目录不存在或无 jar 时返回空列表
     */
    public List<Filter> loadFromDirectory(String pluginDir) {
        Path directory = Path.of(pluginDir == null || pluginDir.isBlank() ? "plugins" : pluginDir);
        if (!Files.isDirectory(directory)) {
            log.info("Filter plugin directory not found, skip external filters: {}", directory.toAbsolutePath());
            return List.of();
        }

        List<URL> jarUrls = listJarUrls(directory);
        if (jarUrls.isEmpty()) {
            log.info("No filter plugin jars found in {}", directory.toAbsolutePath());
            return List.of();
        }

        // 把 plugins 目录下所有 jar 装进同一个 ClassLoader。
        pluginClassLoader = new URLClassLoader(
                jarUrls.toArray(URL[]::new),
                Thread.currentThread().getContextClassLoader());
        log.info("Loaded {} filter plugin jar(s) from {}", jarUrls.size(), directory.toAbsolutePath());

        // 用 LinkedHashMap 去重，避免同一个 Filter 被重复加载。
        Map<String, Filter> filters = new LinkedHashMap<>();
        ServiceLoader<Filter> serviceLoader = ServiceLoader.load(Filter.class, pluginClassLoader);
        for (Filter filter : serviceLoader) {
            filters.putIfAbsent(filter.getClass().getName(), filter);
            log.info(
                    "Discovered plugin filter via SPI: name={}, order={}, class={}",
                    filter.getName(),
                    filter.getOrder(),
                    filter.getClass().getName());
        }
        return new ArrayList<>(filters.values());
    }

    /**
     * 按全限定类名实例化 Filter。
     * 优先用插件 ClassLoader，这样既能加载 plugins 里的类，也能加载 classpath 里的类。
     *
     * @param className Filter 全限定类名
     * @return 新创建的 Filter 实例
     * @throws IllegalStateException 类不存在、未实现 Filter 接口或无无参构造
     */
    public Filter createFilter(String className) {
        try {
            ClassLoader classLoader = pluginClassLoader != null
                    ? pluginClassLoader
                    : Thread.currentThread().getContextClassLoader();
            Class<?> filterClass = Class.forName(className, true, classLoader);
            if (!Filter.class.isAssignableFrom(filterClass)) {
                throw new IllegalStateException("类未实现 Filter 接口：" + className);
            }
            // 要求过滤器提供无参构造，方便统一创建。
            return (Filter) filterClass.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException err) {
            throw new IllegalStateException("创建过滤器失败：" + className, err);
        }
    }

    /** 列出目录下所有 .jar 文件的 URL。 */
    private List<URL> listJarUrls(Path directory) {
        List<URL> jarUrls = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.jar")) {
            for (Path jarPath : stream) {
                try {
                    jarUrls.add(jarPath.toUri().toURL());
                } catch (MalformedURLException err) {
                    throw new IllegalStateException("插件 jar 路径非法：" + jarPath, err);
                }
            }
        } catch (IOException err) {
            throw new IllegalStateException("扫描过滤器插件目录失败：" + directory, err);
        }
        return jarUrls;
    }
}
