package com.rover.gateway.core.runtime;

import com.rover.common.spi.filter.Filter;
import com.rover.gateway.core.config.GatewayRuntimeConfigManager;
import com.rover.gateway.core.discovery.DiscoverySettings;
import com.rover.gateway.core.discovery.DiscoveryType;
import com.rover.common.spi.discovery.ServiceDiscovery;
import com.rover.gateway.core.filter.FilterSettings;
import com.rover.gateway.core.filter.GatewayFilterAssembler;
import com.rover.common.spi.loadbalance.LoadBalancer;
import com.rover.gateway.core.loadbalance.LoadBalancerFactory;
import com.rover.gateway.core.metrics.MetricsRegistry;
import com.rover.gateway.core.metrics.MetricsSettings;
import com.rover.gateway.core.proxy.HttpProxyClient;
import com.rover.gateway.core.trace.TraceBuffer;
import com.rover.gateway.core.trace.TraceSettings;
import com.rover.gateway.core.route.RouteConfig;
import com.rover.gateway.core.route.RouteMatcher;
import com.rover.gateway.core.route.RouteOverlayStore;
import com.rover.gateway.core.route.RouteValidator;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Author: Daylight
 * Created: 2026-08-10 10:33:00
 * Description: Gateway 运行时可变状态：路由、过滤器链、代理客户端、LB 等热更新组件的容器
 */
@Slf4j
@Getter
public class GatewayRuntime {

    /** 监听端口，status 接口展示用。 */
    private final int port;

    /** 管理口鉴权 token（/_manage/**）；空表示不鉴权。 */
    private final String adminToken;

    /** 服务发现配置副本。 */
    private final DiscoverySettings discoverySettings;

    /** 过滤器加载配置，filter.enabled 热更时会改。 */
    private final FilterSettings filterSettings;

    /** HTTP 反向代理客户端，超时支持热更。 */
    private final HttpProxyClient proxyClient;

    /** 指标统一数据源，MetricsFilter 向其累加，管理端点从其读取。 */
    private final MetricsRegistry metricsRegistry = new MetricsRegistry(new MetricsSettings());

    /** 请求链路时间线缓冲（环形，只记慢请求/采样命中）。 */
    private final TraceBuffer traceBuffer = new TraceBuffer();

    /** 链路时间线采集配置，支持热更新。 */
    private final TraceSettings traceSettings = new TraceSettings();

    /** 服务发现客户端，动态模式查实例。 */
    private final ServiceDiscovery serviceDiscovery;

    /** 当前发现模式，决定路由校验和转发逻辑。 */
    private final DiscoveryType discoveryType;

    /** 代理连接超时（毫秒），构造后不变。 */
    private final int connectTimeoutMillis;

    /** 过滤器链组装器。 */
    private final GatewayFilterAssembler assembler = new GatewayFilterAssembler();

    /** 运行时配置管理器。 */
    private final GatewayRuntimeConfigManager configManager;

    /** 路由 overlay 持久化。 */
    private final RouteOverlayStore routeOverlayStore;

    /** 路由清洗校验器。 */
    private final RouteValidator routeValidator;

    /** 当前路由匹配器，热更新时原子替换。 */
    private final AtomicReference<RouteMatcher> routeMatcherRef = new AtomicReference<>();

    /** 当前过滤器链，热更新时原子替换。 */
    private final AtomicReference<List<Filter>> filters = new AtomicReference<>(List.of());

    /** 当前负载均衡器实例。 */
    private final AtomicReference<LoadBalancer> loadBalancer = new AtomicReference<>();

    /** 当前负载均衡策略名，供 status 展示。 */
    private final AtomicReference<String> loadBalanceStrategy = new AtomicReference<>(LoadBalancer.ROUND_ROBIN);

    /**
     * 在途请求准入闸门（有界并发）：超过上限时快速返回 503，避免内存/上游连接被无限堆积。
     * 异步模型下在途请求不占业务线程，闸门与线程数解耦，仅用于背压保护；
     * 默认 CPU×8（下限 64），可用 -Drover.gateway.maxInflight 覆盖。
     */
    private volatile Semaphore processingGate = new Semaphore(defaultProcessingPermits());

    /** 默认准入上限：CPU 核数 × 8，下限 64，可用 -Drover.gateway.maxInflight 覆盖。 */
    private static int defaultProcessingPermits() {
        int fromProp = Integer.getInteger("rover.gateway.maxInflight", 0);
        return fromProp > 0
                ? fromProp
                : Math.max(64, Runtime.getRuntime().availableProcessors() * 8);
    }

    /**
     * 全参数构造：初始化路由表、代理客户端、默认 LB，并组装首版过滤器链。
     *
     * @param port                   监听端口
     * @param routes                 初始路由列表
     * @param connectTimeoutMillis   代理连接超时
     * @param requestTimeoutMillis   代理请求超时
     * @param filterSettings         过滤器配置
     * @param discoverySettings      发现配置
     * @param serviceDiscovery       发现客户端
     * @param configManager          配置管理器
     * @param adminToken             管理口鉴权 token，空表示不鉴权
     */
    public GatewayRuntime(
            int port,
            List<RouteConfig> routes,
            int connectTimeoutMillis,
            int requestTimeoutMillis,
            FilterSettings filterSettings,
            DiscoverySettings discoverySettings,
            ServiceDiscovery serviceDiscovery,
            GatewayRuntimeConfigManager configManager,
            String adminToken) {
        this.port = port;
        this.adminToken = adminToken;
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.discoverySettings = discoverySettings == null ? new DiscoverySettings() : discoverySettings;
        this.discoveryType = this.discoverySettings.getType();
        this.filterSettings = filterSettings == null ? new FilterSettings() : filterSettings;
        this.proxyClient = new HttpProxyClient(connectTimeoutMillis, requestTimeoutMillis);
        this.serviceDiscovery = serviceDiscovery;
        this.configManager = configManager;
        this.routeOverlayStore = new RouteOverlayStore();
        this.routeValidator = new RouteValidator(this.discoveryType);
        this.metricsRegistry.setUpstreamInFlightSupplier(proxyClient::getInFlightCount);
        String pluginDir = this.filterSettings.getPluginDir();
        this.loadBalancer.set(LoadBalancerFactory.create(LoadBalancer.ROUND_ROBIN, pluginDir));
        this.loadBalanceStrategy.set(LoadBalancer.ROUND_ROBIN);
        this.routeMatcherRef.set(new RouteMatcher(routes == null ? List.of() : routes));
        rebuildFilters();
    }

    /** 当前路由匹配器快照。 */
    public RouteMatcher getRouteMatcher() {
        return routeMatcherRef.get();
    }

    /** 尝试获取一个在途处理名额，失败说明已过载（由调用方快速 503）。 */
    public boolean tryAcquireProcessingPermit() {
        return processingGate.tryAcquire();
    }

    /** 释放一个在途处理名额。 */
    public void releaseProcessingPermit() {
        processingGate.release();
    }

    /** @return 当前过滤器链快照，供 Handler 执行 */
    public List<Filter> currentFilters() {
        return filters.get();
    }

    /**
     * 热更新过滤器总开关，会重建过滤器链。
     *
     * @param enabled true 加载插件和配置 Filter，false 仅内置 Filter
     */
    public void applyFilterEnabled(boolean enabled) {
        filterSettings.setEnabled(enabled);
        rebuildFilters();
    }

    /** 热更新代理请求超时（毫秒），必须大于 0。 */
    public void applyRequestTimeoutMillis(long timeoutMillis) {
        proxyClient.setRequestTimeoutMillis(timeoutMillis);
    }

    /** 热更新指标采集总开关，false 时 MetricsFilter 直通，一键降级。 */
    public void applyMetricsEnabled(boolean enabled) {
        metricsRegistry.getSettings().setEnabled(enabled);
        log.info("指标采集开关已切换: enabled={}", enabled);
    }

    /** 热更新指标滑动窗口时长（秒），必须大于 0。 */
    public void applyMetricsWindowSeconds(int windowSeconds) {
        if (windowSeconds <= 0) {
            throw new IllegalArgumentException("metrics.windowSeconds 必须大于 0");
        }
        metricsRegistry.getSettings().setWindowSeconds(windowSeconds);
        log.info("指标窗口时长已切换: windowSeconds={}", windowSeconds);
    }

    /** 热更新链路时间线总开关。 */
    public void applyTraceEnabled(boolean enabled) {
        traceSettings.setEnabled(enabled);
        log.info("链路时间线开关已切换: enabled={}", enabled);
    }

    /** 热更新慢请求阈值（毫秒），必须大于 0。 */
    public void applyTraceSlowThresholdMillis(long thresholdMillis) {
        if (thresholdMillis <= 0) {
            throw new IllegalArgumentException("trace.slowThresholdMillis 必须大于 0");
        }
        traceSettings.setSlowThresholdMillis(thresholdMillis);
        log.info("链路时间线慢请求阈值已切换: slowThresholdMillis={}", thresholdMillis);
    }

    /** 热更新采样率 0~1。 */
    public void applyTraceSampleRate(double sampleRate) {
        if (sampleRate < 0 || sampleRate > 1) {
            throw new IllegalArgumentException("trace.sampleRate 必须在 0~1 之间");
        }
        traceSettings.setSampleRate(sampleRate);
        log.info("链路时间线采样率已切换: sampleRate={}", sampleRate);
    }

    /**
     * 热更新负载均衡策略，会重建 LoadBalancer 和过滤器链。
     *
     * @param strategy 内置名 / SPI name / 自定义类全名
     * @throws IllegalArgumentException 不支持的策略名
     */
    public void applyLoadBalanceStrategy(String strategy) {
        String normalized = strategy == null || strategy.isBlank() ? LoadBalancer.ROUND_ROBIN : strategy.trim();
        LoadBalancer next = LoadBalancerFactory.create(normalized, filterSettings.getPluginDir());
        loadBalanceStrategy.set(next.name() == null ? normalized : next.name());
        loadBalancer.set(next);
        rebuildFilters();
        log.info("负载均衡策略已切换: {}", loadBalanceStrategy.get());
    }

    /**
     * 整表替换路由：校验 → 热替换 matcher → 补订阅 → 落盘 overlay。
     *
     * @param routes 新路由表
     * @return 校验通过并生效的路由列表副本
     * @throws IllegalArgumentException 路由校验失败
     */
    public synchronized List<RouteConfig> applyRoutes(List<RouteConfig> routes) {
        List<RouteConfig> normalized = routeValidator.normalizeAndValidate(routes);
        routeMatcherRef.set(new RouteMatcher(normalized));
        rebuildFilters();
        watchServices(normalized);
        routeOverlayStore.save(normalized);
        log.info("路由已热更新, count={}, overlay={}",
                normalized.size(), routeOverlayStore.getPath().toAbsolutePath());
        return List.copyOf(normalized);
    }

    /** 新增或按 businessPrefix/id 替换单条路由，返回更新后的完整路由表。 */
    public List<RouteConfig> addOrReplaceRoute(RouteConfig route) {
        List<RouteConfig> current = new ArrayList<>(getRouteMatcher().listRoutes());
        String prefix = route.getBusinessPrefix();
        current.removeIf(item -> prefix != null && prefix.equals(item.getBusinessPrefix()));
        if (route.getId() != null && !route.getId().isBlank()) {
            current.removeIf(item -> route.getId().equals(item.getId()));
        }
        current.add(route);
        return applyRoutes(current);
    }

    /** 按 id 或 businessPrefix 删除路由，返回删除后的完整路由表。 */
    public List<RouteConfig> removeRoute(String idOrPrefix) {
        if (idOrPrefix == null || idOrPrefix.isBlank()) {
            throw new IllegalArgumentException("删除路由需要 id 或 businessPrefix");
        }
        List<RouteConfig> current = new ArrayList<>(getRouteMatcher().listRoutes());
        boolean removed = current.removeIf(item ->
                idOrPrefix.equals(item.getId()) || idOrPrefix.equals(item.getBusinessPrefix()));
        if (!removed) {
            throw new IllegalArgumentException("未找到路由: " + idOrPrefix);
        }
        return applyRoutes(current);
    }

    /** NAMESERVER 模式下，对路由里出现的 serviceName 补订 watch。 */
    private void watchServices(List<RouteConfig> routes) {
        if (discoveryType != DiscoveryType.NAMESERVER || serviceDiscovery == null) {
            return;
        }
        for (RouteConfig route : routes) {
            serviceDiscovery.ensureWatch(route.getServiceName(), route.getGroup());
        }
    }

    /** 按当前路由、发现、LB、Filter 配置重新组装过滤器链并原子替换。 */
    private void rebuildFilters() {
        List<Filter> assembled = assembler.assemble(
                filterSettings,
                routeMatcherRef.get(),
                proxyClient,
                discoveryType,
                serviceDiscovery,
                loadBalancer.get(),
                metricsRegistry);
        filters.set(assembled);
    }
}
