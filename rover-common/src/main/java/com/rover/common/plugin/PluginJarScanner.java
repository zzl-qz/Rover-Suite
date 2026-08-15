package com.rover.common.plugin;

import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Author: Daylight
 * Created: 2026-08-05 10:35:00
 * Description: 插件 jar 扫描与 ClassLoader 构建，供 Gateway / Nameserver 等模块复用
 */
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
}
