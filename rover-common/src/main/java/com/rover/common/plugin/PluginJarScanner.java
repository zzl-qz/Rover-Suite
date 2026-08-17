package com.rover.common.plugin;

import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * Author: Daylight
 * Created: 2026-08-05 10:35:00
 * Description: 插件 jar 扫描与 ClassLoader 构建，供 Gateway / Nameserver 等模块复用
 */
@Slf4j
public final class PluginJarScanner {

    private PluginJarScanner() {
    }

    /** 列出目录下所有 .jar 的 URL。 */
    public static List<URL> listJars(Path directory) {
        List<URL> jars = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.jar")) {
            for (Path jar : stream) {
                try {
                    jars.add(jar.toUri().toURL());
                } catch (MalformedURLException ex) {
                    throw new IllegalStateException("插件 jar 路径非法: " + jar, ex);
                }
            }
        } catch (Exception ex) {
            throw new IllegalStateException("扫描插件目录失败: " + directory, ex);
        }
        return jars;
    }

    /** 基于 jar URL 建 ClassLoader：先查插件 jar，再委托应用 ClassLoader。 */
    public static URLClassLoader newClassLoader(List<URL> jars) {
        return new URLClassLoader(
                jars.toArray(URL[]::new),
                Thread.currentThread().getContextClassLoader());
    }

    /**
     * 安静关闭插件 ClassLoader，释放 jar 文件句柄。
     * 热更新时必须先关掉旧的再换新的，否则句柄和元数据会堆起来。
     */
    public static void closeQuietly(URLClassLoader classLoader) {
        if (classLoader == null) {
            return;
        }
        try {
            classLoader.close();
        } catch (Exception ex) {
            log.warn("关闭插件 ClassLoader 失败: {}", ex.toString());
        }
    }
}
