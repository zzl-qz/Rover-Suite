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
 * 插件 jar 扫描与 ClassLoader 构建。
 *
 * 套件级公共能力：Gateway / Nameserver 等模块都可复用，
 * 业务模块只关心自己的 SPI 装配，不各自再写一遍扫 jar。
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
