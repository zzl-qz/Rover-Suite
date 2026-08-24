package com.rover.gateway.core.filter;

import com.rover.common.spi.filter.Filter;
import com.rover.common.plugin.PluginSpiLoader;
import com.rover.gateway.core.discovery.DiscoveryType;
import com.rover.common.spi.discovery.ServiceDiscovery;
import com.rover.common.spi.loadbalance.LoadBalancer;
import com.rover.gateway.core.metrics.MetricsFilter;
import com.rover.gateway.core.metrics.MetricsRegistry;
import com.rover.gateway.core.filter.ratelimit.RateLimitFilter;
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
 */
@Slf4j
public class GatewayFilterAssembler {

    /** 负责从 plugins 目录和 classpath 加载用户扩展 Filter。 */
    private final PluginFilterLoader pluginFilterLoader = new PluginFilterLoader();

    /**
     * @DL 扩展 API：供静态模式独立组装过滤器链；Gateway 运行时使用全参数重载。
     */
    public List<Filter> assemble(
            FilterSettings settings,
            RouteMatcher routeMatcher,
            HttpProxyClient proxyClient) {
        return assemble(settings, routeMatcher, proxyClient, DiscoveryType.STATIC, null, null, null);
    }

    /** 全参数组装过滤器链：访问日志 → 指标采集 → 插件/配置 Filter → 路由转发终端。 */
    public List<Filter> assemble(
            FilterSettings settings,
            RouteMatcher routeMatcher,
            HttpProxyClient proxyClient,
            DiscoveryType discoveryType,
            ServiceDiscovery serviceDiscovery,
            LoadBalancer loadBalancer,
            MetricsRegistry metricsRegistry) {
        // key 用类名，避免同名过滤器被重复加入。
        Map<String, Filter> filters = new LinkedHashMap<>();

        // 访问日志可关：默认装配，内容走 debug，避免热路径 info 刷盘。
        if (settings == null || settings.isAccessLog()) {
            AccessLogFilter accessLogFilter = new AccessLogFilter();
            filters.put(accessLogFilter.getClass().getName(), accessLogFilter);
        } else {
            log.info("访问日志过滤器已关闭 (filters.accessLog=false)");
        }

        // 指标采集始终装配，内部受 metrics.enabled 总开关控制，可一键降级。
        if (metricsRegistry != null) {
            MetricsFilter metricsFilter = new MetricsFilter(metricsRegistry);
            filters.put(metricsFilter.getClass().getName(), metricsFilter);
        }

        // 本地限流默认关闭，开启后放在外部插件之前，超额请求尽早短路。
        if (settings != null && settings.getRateLimit().isEnabled()) {
            RateLimitFilter rateLimitFilter = new RateLimitFilter(settings.getRateLimit());
            filters.put(rateLimitFilter.getClass().getName(), rateLimitFilter);
        }

        if (settings == null || settings.isEnabled()) {
            // 1) 从 plugins 目录自动发现 SPI 过滤器。
            String pluginDir = settings == null ? PluginSpiLoader.DEFAULT_DIR : settings.getPluginDir();
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
            pluginFilterLoader.close();
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

    /** Gateway 关闭时释放当前 Filter 插件 loader。 */
    public void close() {
        pluginFilterLoader.close();
    }
}
