package com.rover.gateway.core.loadbalance;

import com.rover.common.plugin.PluginJarScanner;
import com.rover.common.plugin.PluginSpiLoader;
import com.rover.common.plugin.PluginSpiLoader.PluginLoadResult;
import com.rover.common.spi.loadbalance.LoadBalancer;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * Author: Daylight
 * Created: 2026-08-06 10:50:00
 * Description: 按策略名创建负载均衡器：内置 + plugins SPI + 全限定类名
 */
@Slf4j
public final class LoadBalancerFactory {

    /**
     * 当前仍在使用的插件 LB 所依赖的 ClassLoader。
     * 切策略时关掉上一个，避免 create 每次 new loader 却从不 close。
     */
    private static volatile URLClassLoader retainedPluginClassLoader;

    private LoadBalancerFactory() {
    }

    public static LoadBalancer create(String strategy) {
        return create(strategy, PluginSpiLoader.DEFAULT_DIR);
    }

    public static LoadBalancer create(String strategy, String pluginDir) {
        String normalized = strategy == null || strategy.isBlank()
                ? LoadBalancer.ROUND_ROBIN
                : strategy.trim();
        String key = normalized.toLowerCase(Locale.ROOT);

        Map<String, LoadBalancer> builtins = builtins();
        if (builtins.containsKey(key)) {
            // 内置策略不依赖插件 loader；若之前挂着插件 loader，一并释放
            releaseRetainedPluginClassLoader();
            return builtins.get(key);
        }

        // plugins SPI：META-INF/services/com.rover.common.spi.loadbalance.LoadBalancer
        PluginLoadResult<LoadBalancer> result = PluginSpiLoader.load(LoadBalancer.class, pluginDir);
        try {
            for (LoadBalancer plugin : result.instances()) {
                if (plugin.name() != null && plugin.name().equalsIgnoreCase(normalized)) {
                    log.info("使用插件负载均衡: name={}, class={}", plugin.name(), plugin.getClass().getName());
                    retainPluginClassLoader(result.classLoader());
                    // loader 已移交 retain，避免 finally 再关
                    result = new PluginLoadResult<>(
                            result.instances(), null, result.jarCount(), result.directory());
                    return plugin;
                }
            }
        } finally {
            result.close();
        }

        // 全限定类名：实例可能在首次 choose 时才加载同 jar 的辅助类，loader 必须随实例保留。
        if (normalized.contains(".")) {
            ClassLoader loader = PluginSpiLoader.classLoaderFor(pluginDir);
            try {
                LoadBalancer custom = PluginSpiLoader.newInstance(LoadBalancer.class, normalized, loader);
                if (loader instanceof URLClassLoader urlClassLoader) {
                    retainPluginClassLoader(urlClassLoader);
                } else {
                    releaseRetainedPluginClassLoader();
                }
                log.info("使用自定义负载均衡类: {}", custom.getClass().getName());
                return custom;
            } catch (RuntimeException ex) {
                if (loader instanceof URLClassLoader urlClassLoader) {
                    PluginJarScanner.closeQuietly(urlClassLoader);
                }
                throw ex;
            }
        }

        throw new IllegalArgumentException(
                "不支持的负载均衡策略: " + strategy
                        + "，可选: round_robin/random/weighted_round_robin/ip_hash/least_connections"
                        + "，或 plugins SPI name，或自定义类全名");
    }

    public static List<String> supportedNames(String pluginDir) {
        List<String> names = new ArrayList<>(builtins().keySet());
        // 仅枚举名字，实例不长期持有，扫完立刻 close ClassLoader
        try (PluginLoadResult<LoadBalancer> result =
                PluginSpiLoader.load(LoadBalancer.class, pluginDir)) {
            for (LoadBalancer plugin : result.instances()) {
                if (plugin.name() != null && !plugin.name().isBlank()) {
                    names.add(plugin.name());
                }
            }
        }
        return names;
    }

    private static void retainPluginClassLoader(URLClassLoader next) {
        URLClassLoader previous = retainedPluginClassLoader;
        retainedPluginClassLoader = next;
        if (previous != null && previous != next) {
            PluginJarScanner.closeQuietly(previous);
        }
    }

    private static void releaseRetainedPluginClassLoader() {
        URLClassLoader previous = retainedPluginClassLoader;
        retainedPluginClassLoader = null;
        PluginJarScanner.closeQuietly(previous);
    }

    /** Gateway 关闭时释放当前负载均衡插件 loader。 */
    public static void shutdown() {
        releaseRetainedPluginClassLoader();
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
}
