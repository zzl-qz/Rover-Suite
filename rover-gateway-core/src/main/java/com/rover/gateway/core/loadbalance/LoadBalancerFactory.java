package com.rover.gateway.core.loadbalance;

import com.rover.common.plugin.PluginSpiLoader;
import com.rover.common.spi.loadbalance.LoadBalancer;
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
            return builtins.get(key);
        }

        // plugins SPI：META-INF/services/com.rover.common.spi.loadbalance.LoadBalancer
        for (LoadBalancer plugin : PluginSpiLoader.load(LoadBalancer.class, pluginDir).instances()) {
            if (plugin.name() != null && plugin.name().equalsIgnoreCase(normalized)) {
                log.info("使用插件负载均衡: name={}, class={}", plugin.name(), plugin.getClass().getName());
                return plugin;
            }
        }

        // 全限定类名
        if (normalized.contains(".")) {
            LoadBalancer custom = PluginSpiLoader.newInstance(LoadBalancer.class, normalized, pluginDir);
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
        for (LoadBalancer plugin : PluginSpiLoader.load(LoadBalancer.class, pluginDir).instances()) {
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
}
