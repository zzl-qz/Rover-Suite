package com.rover.gateway.core.filter;

import com.rover.common.spi.filter.Filter;
import com.rover.gateway.core.discovery.DiscoveryType;
import com.rover.common.spi.discovery.ServiceDiscovery;
import com.rover.common.spi.loadbalance.LoadBalancer;
import com.rover.gateway.core.proxy.HttpProxyClient;
import com.rover.gateway.core.route.RouteMatcher;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * Author: Daylight
 * Created: 2026-08-08 16:53:00
 * Description: 组装内置过滤器、配置过滤器和 plugins 外挂过滤器
 *
 * 这个类是什么：Gateway 过滤器链的工厂，把各类 Filter 按 order 排好序。
 * 核心职责：①始终加入 AccessLogFilter；②按配置加载 plugins jar 和显式类名；
 * ③最后追加 RouteAndProxyFilter 作为终端节点。
 * 被谁用：GatewayRuntime 在启动和热更新时调用 assemble 重建过滤器链。
 */
@Slf4j
public class GatewayFilterAssembler {

    /** 负责从 plugins 目录和 classpath 加载用户扩展 Filter。 */
    private final PluginFilterLoader pluginFilterLoader = new PluginFilterLoader();

    /**
     * 静态模式组装过滤器链。
     *
     * @param settings     过滤器加载配置
     * @param routeMatcher 路由匹配器
     * @param proxyClient  HTTP 代理客户端
     * @return 按 order 排序后的不可变过滤器列表（含终端 RouteAndProxyFilter）
     */
    public List<Filter> assemble(
            FilterSettings settings,
            RouteMatcher routeMatcher,
            HttpProxyClient proxyClient) {
        return assemble(settings, routeMatcher, proxyClient, DiscoveryType.STATIC, null, null);
    }

    /**
     * 全参数组装过滤器链：访问日志 → 插件/配置 Filter → 路由转发。
     *
     * @param settings          过滤器加载配置
     * @param routeMatcher      路由匹配器
     * @param proxyClient       HTTP 代理客户端
     * @param discoveryType     上游发现模式
     * @param serviceDiscovery  服务发现，动态模式使用
     * @param loadBalancer      负载均衡器，动态模式使用
     * @return 按 order 排序后的不可变过滤器列表
     */
    public List<Filter> assemble(
            FilterSettings settings,
            RouteMatcher routeMatcher,
            HttpProxyClient proxyClient,
            DiscoveryType discoveryType,
            ServiceDiscovery serviceDiscovery,
            LoadBalancer loadBalancer) {
        // key 用类名，避免同名过滤器被重复加入。
        Map<String, Filter> filters = new LinkedHashMap<>();

        // 内置访问日志始终开启，保证主链路可观测。
        AccessLogFilter accessLogFilter = new AccessLogFilter();
        filters.put(accessLogFilter.getClass().getName(), accessLogFilter);

        if (settings == null || settings.isEnabled()) {
            // 1) 从 plugins 目录自动发现 SPI 过滤器。
            String pluginDir = settings == null ? "plugins" : settings.getPluginDir();
            for (Filter pluginFilter : pluginFilterLoader.loadFromDirectory(pluginDir)) {
                filters.putIfAbsent(pluginFilter.getClass().getName(), pluginFilter);
            }

            // 2) 再加载配置文件里显式指定的过滤器类名。
            List<String> classes = settings == null ? List.of() : settings.getClasses();
            if (classes != null) {
                for (String className : classes) {
                    if (className == null || className.isBlank()) {
                        continue;
                    }
                    Filter configuredFilter = pluginFilterLoader.createFilter(className.trim());
                    filters.put(configuredFilter.getClass().getName(), configuredFilter);
                    log.info(
                            "Loaded configured filter: name={}, order={}, class={}",
                            configuredFilter.getName(),
                            configuredFilter.getOrder(),
                            configuredFilter.getClass().getName());
                }
            }
        } else {
            log.info("External filters disabled by config, only builtin filters will run");
        }

        // 先按 order 排序，数值越小越先执行。
        List<Filter> orderedFilters = new ArrayList<>(filters.values());
        orderedFilters.sort(Comparator.comparingInt(Filter::getOrder));

        // 终端过滤器固定放最后，负责路由匹配和真实转发。
        orderedFilters.add(new RouteAndProxyFilter(
                routeMatcher, proxyClient, discoveryType, serviceDiscovery, loadBalancer));

        log.info("Gateway filter chain ready, size={}", orderedFilters.size());
        for (Filter filter : orderedFilters) {
            log.info("Filter chain item: order={}, name={}, class={}",
                    filter.getOrder(),
                    filter.getName(),
                    filter.getClass().getName());
        }
        return List.copyOf(orderedFilters);
    }
}
