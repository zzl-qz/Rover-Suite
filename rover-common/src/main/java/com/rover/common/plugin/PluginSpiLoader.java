package com.rover.common.plugin;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;

/**
 * 插件 SPI 加载骨架：扫 jar → ClassLoader → ServiceLoader / 反射实例化。
 *
 * 和 PluginJarScanner 配套；各模块只写自己的策略匹配，不重复装载逻辑。
 */
public final class PluginSpiLoader {

    public static final String DEFAULT_DIR = "plugins";

    private PluginSpiLoader() {
    }

    /** 解析插件目录，空则用默认 plugins。 */
    public static Path resolveDirectory(String pluginDir) {
        return Path.of(pluginDir == null || pluginDir.isBlank() ? DEFAULT_DIR : pluginDir);
    }

    /**
     * 从插件目录 SPI 加载某接口的全部实现（按类名去重）。
     * 目录不存在或无 jar 时返回空结果，classLoader 为 null。
     */
    public static <T> PluginLoadResult<T> load(Class<T> type, String pluginDir) {
        Objects.requireNonNull(type, "type");
        Path directory = resolveDirectory(pluginDir);
        if (!Files.isDirectory(directory)) {
            return PluginLoadResult.empty();
        }
        List<URL> jars = PluginJarScanner.listJars(directory);
        if (jars.isEmpty()) {
            return new PluginLoadResult<>(List.of(), null, 0, directory);
        }
        URLClassLoader classLoader = PluginJarScanner.newClassLoader(jars);
        Map<String, T> unique = new LinkedHashMap<>();
        for (T instance : ServiceLoader.load(type, classLoader)) {
            unique.putIfAbsent(instance.getClass().getName(), instance);
        }
        return new PluginLoadResult<>(List.copyOf(unique.values()), classLoader, jars.size(), directory);
    }

    /**
     * 为插件目录构建 ClassLoader；无 jar 时退回当前线程 ContextClassLoader。
     */
    public static ClassLoader classLoaderFor(String pluginDir) {
        Path directory = resolveDirectory(pluginDir);
        if (!Files.isDirectory(directory)) {
            return Thread.currentThread().getContextClassLoader();
        }
        List<URL> jars = PluginJarScanner.listJars(directory);
        if (jars.isEmpty()) {
            return Thread.currentThread().getContextClassLoader();
        }
        return PluginJarScanner.newClassLoader(jars);
    }

    /**
     * 按全限定类名实例化，要求无参构造且实现 type。
     *
     * @param type       目标接口/父类型
     * @param className  全限定类名
     * @param classLoader 用哪个 ClassLoader；null 则用 ContextClassLoader
     */
    public static <T> T newInstance(Class<T> type, String className, ClassLoader classLoader) {
        Objects.requireNonNull(type, "type");
        if (className == null || className.isBlank()) {
            throw new IllegalArgumentException("类名不能为空");
        }
        ClassLoader loader = classLoader != null
                ? classLoader
                : Thread.currentThread().getContextClassLoader();
        try {
            Class<?> clazz = Class.forName(className.trim(), true, loader);
            if (!type.isAssignableFrom(clazz)) {
                throw new IllegalArgumentException("类未实现 " + type.getName() + ": " + className);
            }
            return type.cast(clazz.getDeclaredConstructor().newInstance());
        } catch (ReflectiveOperationException ex) {
            throw new IllegalArgumentException("创建插件实例失败: " + className, ex);
        }
    }

    /** 便捷：用插件目录（或 classpath）反射创建。 */
    public static <T> T newInstance(Class<T> type, String className, String pluginDir) {
        return newInstance(type, className, classLoaderFor(pluginDir));
    }

    /**
     * SPI 加载结果。
     *
     * @param instances   去重后的实例
     * @param classLoader 插件 ClassLoader；未加载到 jar 时为 null
     * @param jarCount    扫到的 jar 数量
     * @param directory   实际扫描目录
     */
    public record PluginLoadResult<T>(
            List<T> instances,
            URLClassLoader classLoader,
            int jarCount,
            Path directory) {

        public static <T> PluginLoadResult<T> empty() {
            return new PluginLoadResult<>(List.of(), null, 0, null);
        }

        public boolean isEmpty() {
            return instances == null || instances.isEmpty();
        }
    }
}
