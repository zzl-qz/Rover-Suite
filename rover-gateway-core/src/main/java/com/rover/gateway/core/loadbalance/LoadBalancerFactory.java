package com.rover.gateway.core.loadbalance;

import com.rover.common.spi.loadbalance.LoadBalancer;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ServiceLoader;
import lombok.extern.slf4j.Slf4j;

/**
 * Author: Daylight
 * Created: 2026-08-13
 * Description: 按策略名创建 LB：内置 + plugins SPI + 全限定类名
 *
 * 配置 gateway.loadbalance.strategy：
 * - round_robin（默认）/ random / weighted_round_robin / ip_hash / least_connections
 * - 自定义 SPI 的 name()
 * - 或直接写实现类全名（无参构造）
 */
@Slf4j
public final class LoadBalancerFactory {

    private LoadBalancerFactory() {
    }

    public static LoadBalancer create(String strategy) {
        return create(strategy, "plugins");
    }

    public static LoadBalancer create(String strategy, String pluginDir) {
        String normalized = strategy == null || strategy.isBlank()
                ? "round_robin"
                : strategy.trim();
        String key = normalized.toLowerCase(Locale.ROOT);

        Map<String, LoadBalancer> builtins = builtins();
        if (builtins.containsKey(key)) {
            return builtins.get(key);
        }

        // plugins SPI：META-INF/services/com.rover.common.spi.loadbalance.LoadBalancer
        for (LoadBalancer plugin : loadPlugins(pluginDir)) {
            if (plugin.name() != null && plugin.name().equalsIgnoreCase(normalized)) {
                log.info("使用插件负载均衡: name={}, class={}", plugin.name(), plugin.getClass().getName());
                return plugin;
            }
        }

        // 全限定类名
        if (normalized.contains(".")) {
            LoadBalancer custom = instantiate(normalized, pluginDir);
            log.info("使用自定义负载均衡类: {}", custom.getClass().getName());
            return custom;
        }

        throw new IllegalArgumentException(
                "不支持的负载均衡策略: " + strategy
                        + "，可选: round_robin/random/weighted_round_robin/ip_hash/least_connections"
                        + "，或 plugins SPI name，或自定义类全名");
    }

    public static List<String> supportedNames(String pluginDir) {
        List<String> names = new ArrayList<>(builtins().keySet());
        for (LoadBalancer plugin : loadPlugins(pluginDir)) {
            if (plugin.name() != null && !plugin.name().isBlank()) {
                names.add(plugin.name());
            }
        }
        return names;
    }

    private static Map<String, LoadBalancer> builtins() {
        Map<String, LoadBalancer> map = new LinkedHashMap<>();
        put(map, new RoundRobinLoadBalancer());
        put(map, new RandomLoadBalancer());
        put(map, new WeightedRoundRobinLoadBalancer());
        put(map, new IpHashLoadBalancer());
        put(map, new LeastConnectionsLoadBalancer());
        return map;
    }

    private static void put(Map<String, LoadBalancer> map, LoadBalancer balancer) {
        map.put(balancer.name().toLowerCase(Locale.ROOT), balancer);
    }

    private static List<LoadBalancer> loadPlugins(String pluginDir) {
        Path directory = Path.of(pluginDir == null || pluginDir.isBlank() ? "plugins" : pluginDir);
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        List<URL> jars = listJars(directory);
        if (jars.isEmpty()) {
            return List.of();
        }
        URLClassLoader classLoader = new URLClassLoader(
                jars.toArray(URL[]::new),
                Thread.currentThread().getContextClassLoader());
        List<LoadBalancer> loaded = new ArrayList<>();
        for (LoadBalancer balancer : ServiceLoader.load(LoadBalancer.class, classLoader)) {
            loaded.add(balancer);
            log.info("发现负载均衡插件: name={}, class={}",
                    balancer.name(), balancer.getClass().getName());
        }
        return loaded;
    }

    private static LoadBalancer instantiate(String className, String pluginDir) {
        try {
            ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
            Path directory = Path.of(pluginDir == null || pluginDir.isBlank() ? "plugins" : pluginDir);
            if (Files.isDirectory(directory)) {
                List<URL> jars = listJars(directory);
                if (!jars.isEmpty()) {
                    classLoader = new URLClassLoader(
                            jars.toArray(URL[]::new),
                            Thread.currentThread().getContextClassLoader());
                }
            }
            Class<?> clazz = Class.forName(className, true, classLoader);
            if (!LoadBalancer.class.isAssignableFrom(clazz)) {
                throw new IllegalArgumentException("类未实现 LoadBalancer: " + className);
            }
            return (LoadBalancer) clazz.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException ex) {
            throw new IllegalArgumentException("创建负载均衡失败: " + className, ex);
        }
    }

    private static List<URL> listJars(Path directory) {
        List<URL> jars = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.jar")) {
            for (Path jar : stream) {
                try {
                    jars.add(jar.toUri().toURL());
                } catch (MalformedURLException ex) {
                    throw new IllegalStateException("插件路径非法: " + jar, ex);
                }
            }
        } catch (Exception ex) {
            throw new IllegalStateException("扫描负载均衡插件目录失败: " + directory, ex);
        }
        return jars;
    }
}
