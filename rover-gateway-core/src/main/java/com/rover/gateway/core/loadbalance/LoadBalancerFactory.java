package com.rover.gateway.core.loadbalance;

import com.rover.common.plugin.PluginJarScanner;
import com.rover.common.plugin.PluginSpiLoader;
import com.rover.common.plugin.PluginSpiLoader.PluginLoadResult;
import com.rover.common.spi.loadbalance.LoadBalancer;
import com.rover.common.spi.loadbalance.BuiltinLoadBalanceStrategy;
import java.net.URLClassLoader;
import java.util.LinkedHashMap;
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

    /**
     * @DL 扩展 API：供独立调用方按默认 plugins 目录创建策略；Gateway 运行时使用带目录的重载。
     */
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

        // 全限定类名：不依赖 SPI 声明，也不应该被 plugins 里的 SPI 状态影响。
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

        // plugins SPI：META-INF/services/com.rover.common.spi.loadbalance.LoadBalancer
        PluginLoadResult<LoadBalancer> result = PluginSpiLoader.load(LoadBalancer.class, pluginDir);
        try {
            Map<String, String> owners = strategyOwners(builtins);
            LoadBalancer selected = null;
            for (LoadBalancer plugin : result.instances()) {
                registerPluginName(owners, plugin);
                if (plugin.name() != null && plugin.name().equalsIgnoreCase(normalized)) {
                    selected = plugin;
                }
            }
            if (selected != null) {
                log.info("使用插件负载均衡: name={}, class={}", selected.name(), selected.getClass().getName());
                retainPluginClassLoader(result.classLoader());
                // loader 已移交 retain，避免 finally 再关
                result = new PluginLoadResult<>(
                        result.instances(), null, result.jarCount(), result.directory());
                return selected;
            }
        } finally {
            result.close();
        }

        throw new IllegalArgumentException(
                "不支持的负载均衡策略: " + strategy
                        + "，内置可选: " + String.join("/", BuiltinLoadBalanceStrategy.configNames())
                        + "，或 plugins SPI name，或自定义类全名");
    }

    /** 策略名称在整个 Gateway 内大小写不敏感，重复时不能依赖 JAR 扫描顺序。 */
    private static void registerPluginName(Map<String, String> owners, LoadBalancer plugin) {
        String name = plugin.name();
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("负载均衡插件名称不能为空: " + plugin.getClass().getName());
        }
        String normalized = name.trim().toLowerCase(Locale.ROOT);
        String owner = plugin.getClass().getName();
        String existing = owners.putIfAbsent(normalized, owner);
        if (existing != null) {
            throw new IllegalArgumentException(
                    "负载均衡策略名称重复: " + name + "，冲突实现: " + existing + " / " + owner);
        }
    }

    private static Map<String, String> strategyOwners(Map<String, LoadBalancer> builtins) {
        Map<String, String> owners = new LinkedHashMap<>();
        for (Map.Entry<String, LoadBalancer> entry : builtins.entrySet()) {
            owners.put(entry.getKey(), "内置策略 " + entry.getValue().getClass().getName());
        }
        return owners;
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
