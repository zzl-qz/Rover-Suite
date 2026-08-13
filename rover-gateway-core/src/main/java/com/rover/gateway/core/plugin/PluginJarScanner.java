package com.rover.gateway.core.plugin;

import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 插件 jar 扫描与 ClassLoader 构建工具。
 *
 * 这个类是什么：网关插件（Filter / LoadBalancer 等）加载的公共能力。
 * 核心职责：①扫描指定目录下的 .jar 并转成 URL 列表；②基于这些 jar 构建 URLClassLoader。
 * 被谁用：PluginFilterLoader 与 LoadBalancerFactory。
 */
public final class PluginJarScanner {

    private PluginJarScanner() {
    }

    /** 列出目录下所有 .jar 文件的 URL。 */
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

    /** 基于给定 jar URL 构建插件 ClassLoader，先查插件 jar 再委托应用 ClassLoader。 */
    public static URLClassLoader newClassLoader(List<URL> jars) {
        return new URLClassLoader(jars.toArray(URL[]::new), Thread.currentThread().getContextClassLoader());
    }
}
