package com.rover.gateway.core.runtime;

import com.rover.common.manage.ManageApiException;
import com.rover.common.spi.filter.Filter;
import com.rover.gateway.core.config.GatewayDefaults;
import com.rover.gateway.core.config.GatewayRuntimeConfigManager;
import com.rover.gateway.core.config.GatewaySystemProperties;
import com.rover.gateway.core.discovery.DiscoverySettings;
import com.rover.gateway.core.discovery.DiscoveryType;
import com.rover.common.spi.discovery.ServiceDiscovery;
import com.rover.common.spi.discovery.ServiceDiscoveryStatus;
import com.rover.gateway.core.filter.FilterSettings;
import com.rover.gateway.core.filter.GatewayFilterAssembler;
import com.rover.gateway.core.filter.circuit.CircuitBreakerSettings;
import com.rover.gateway.core.filter.circuit.InstanceCircuitBreaker;
import com.rover.gateway.core.filter.ratelimit.RateLimitSettings;
import com.rover.common.spi.loadbalance.LoadBalancer;
import com.rover.common.spi.plugin.ConfigurablePlugin;
import com.rover.gateway.core.loadbalance.LoadBalancerFactory;
import com.rover.gateway.core.loadbalance.StaticUpstreamCluster;
import com.rover.gateway.core.metrics.MetricsRegistry;
import com.rover.gateway.core.metrics.MetricsSettings;
import com.rover.gateway.core.proxy.HttpProxyClient;
import com.rover.gateway.core.trace.TraceBuffer;
import com.rover.gateway.core.trace.TraceSettings;
import com.rover.gateway.core.route.RouteConfig;
import com.rover.gateway.core.route.RouteDiff;
import com.rover.gateway.core.route.RouteMatcher;
import com.rover.gateway.core.route.RouteOverlayStore;
import com.rover.gateway.core.route.RouteTarget;
import com.rover.gateway.core.route.RouteValidator;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;
import lombok.AccessLevel;
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

    /** 进程内熔断器，关着时不塞进 RouteAndProxyFilter。 */
    private final InstanceCircuitBreaker circuitBreaker;

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

    /** 最近若干次已应用快照的条数上限，超出后最旧的被丢弃。 */
    public static final int MAX_APPLIED_HISTORY = 5;

    /** 操作记录条数上限，超出后按插入顺序淘汰最旧的。 */
    public static final int MAX_OPERATIONS = 200;

    /** 已确认版本号，与落盘 overlay 上的 revision 一致；只在 synchronized 内改。 */
    private int revision;

    /** 产生当前版本的操作 ID；空表示当前版本不是由管理口写入的。 */
    private String appliedOperationId = "";

    /** 最近已应用快照，供回滚；重启后清空（当前版本仍在，只是更早的快照没了）。 */
    @Getter(AccessLevel.NONE)
    private final Deque<VersionedRoutes> history = new ArrayDeque<>();

    /** 操作记录：幂等重放与「请求超时后确认是否执行」都靠它。 */
    @Getter(AccessLevel.NONE)
    private final Map<String, OperationRecord> operations = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, OperationRecord> eldest) {
            return size() > MAX_OPERATIONS;
        }
    };

    /**
     * 在途请求准入闸门（有界并发）：超过上限时快速返回 503，避免内存/上游连接被无限堆积。
     * 异步模型下在途请求不占业务线程，闸门与线程数解耦，仅用于背压保护；
     * 默认 CPU×8（下限 64）。YAML server.maxInflight 或 -Drover.gateway.maxInflight 可覆盖。
     */
    private final int processingPermits = defaultProcessingPermits();
    private final Semaphore processingGate = new Semaphore(processingPermits);

    /** YAML 启动时会写进系统属性；没写就认 -D，再没有用 CPU 默认。 */
    private static int defaultProcessingPermits() {
        return GatewayDefaults.intPropertyOrDefault(
                GatewaySystemProperties.MAX_INFLIGHT, GatewayDefaults.defaultMaxInflight());
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
        this(port, routes, connectTimeoutMillis, requestTimeoutMillis, filterSettings, discoverySettings,
                serviceDiscovery, configManager, adminToken, new RouteOverlayStore());
    }

    /**
     * 全参数构造（可注入 overlay 存储，便于测试指定落盘路径与制造写盘失败）。
     *
     * @param routeOverlayStore 路由 overlay 持久化
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
            String adminToken,
            RouteOverlayStore routeOverlayStore) {
        this.port = port;
        this.adminToken = adminToken;
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.discoverySettings = discoverySettings == null ? new DiscoverySettings() : discoverySettings;
        this.discoveryType = this.discoverySettings.getType();
        this.filterSettings = filterSettings == null ? new FilterSettings() : filterSettings;
        this.circuitBreaker = new InstanceCircuitBreaker(this.filterSettings.getCircuitBreaker());
        this.proxyClient = new HttpProxyClient(connectTimeoutMillis, requestTimeoutMillis);
        this.serviceDiscovery = serviceDiscovery;
        this.configManager = configManager;
        this.routeOverlayStore = routeOverlayStore;
        this.routeValidator = new RouteValidator(this.discoveryType);
        this.metricsRegistry.setUpstreamInFlightSupplier(proxyClient::getInFlightCount);
        this.metricsRegistry.setDiscoveryStatusSupplier(() -> serviceDiscovery instanceof ServiceDiscoveryStatus status
                ? status.status() : Map.of("supported", false));
        String pluginDir = this.filterSettings.getPluginDir();
        this.loadBalancer.set(LoadBalancerFactory.create(LoadBalancer.ROUND_ROBIN, pluginDir));
        this.loadBalanceStrategy.set(LoadBalancer.ROUND_ROBIN);
        List<RouteConfig> initialRoutes = routeValidator.normalizeAndValidate(
                routes == null ? List.of() : routes);
        this.routeMatcherRef.set(new RouteMatcher(initialRoutes));
        StaticUpstreamCluster.rebuild(initialRoutes);
        // 指标按版本归因：注入配置声明的版本目标，供「声明版本 vs 观测版本」核对
        this.metricsRegistry.setRouteTargetsSupplier(this::declaredTargetsOf);
        // 0 号版本先入历史，回滚到「当前版本」于是也有据可依
        history.addLast(new VersionedRoutes(0, initialRoutes));
        rebuildFilters();
    }

    /** 当前路由匹配器快照。 */
    public RouteMatcher getRouteMatcher() {
        return routeMatcherRef.get();
    }

    /** routeId -> 配置声明的版本目标（RouteConfig.getTargets()）；未命中返回空列表。 */
    private List<RouteTarget> declaredTargetsOf(String routeId) {
        if (routeId == null) {
            return List.of();
        }
        for (RouteConfig route : getRouteMatcher().listRoutes()) {
            if (routeId.equals(route.getId())) {
                return route.getTargets() == null ? List.of() : route.getTargets();
            }
        }
        return List.of();
    }

    /** 尝试获取一个在途处理名额，失败说明已过载（由调用方快速 503）。 */
    public boolean tryAcquireProcessingPermit() {
        return processingGate.tryAcquire();
    }

    /** 释放一个在途处理名额。 */
    public void releaseProcessingPermit() {
        processingGate.release();
    }

    /** 在途上限（Semaphore 许可数）。 */
    public int maxInflight() {
        return processingPermits;
    }

    /** 当前已占用的在途名额。只给 503 日志 / 管理口用，别放热路径。 */
    public int inflightUsed() {
        return Math.max(0, processingPermits - processingGate.availablePermits());
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

    /** 热更新内置本地限流开关，重建过滤器链让新 Filter 立刻接管。 */
    public void applyRateLimitEnabled(boolean enabled) {
        updateRateLimit(() -> filterSettings.getRateLimit().setEnabled(enabled));
    }

    /** 热更新内置限流算法。 */
    public void applyRateLimitAlgorithm(String algorithm) {
        updateRateLimit(() -> filterSettings.getRateLimit().setAlgorithm(algorithm));
    }

    /** 热更新配额维度：整台 Gateway 或按路径。 */
    public void applyRateLimitKey(String key) {
        updateRateLimit(() -> filterSettings.getRateLimit().setKey(key));
    }

    /** 热更新令牌桶每秒补充量。 */
    public void applyRateLimitPermitsPerSecond(long permitsPerSecond) {
        updateRateLimit(() -> filterSettings.getRateLimit().setPermitsPerSecond(permitsPerSecond));
    }

    /** 热更新令牌桶最大突发量。 */
    public void applyRateLimitBurst(long burst) {
        updateRateLimit(() -> filterSettings.getRateLimit().setBurst(burst));
    }

    /** 热更新滑动窗口最大请求数。 */
    public void applyRateLimitLimit(long limit) {
        updateRateLimit(() -> filterSettings.getRateLimit().setLimit(limit));
    }

    /** 热更新滑动窗口时长。 */
    public void applyRateLimitWindowSeconds(int windowSeconds) {
        updateRateLimit(() -> filterSettings.getRateLimit().setWindowSeconds(windowSeconds));
    }

    /** 热更新熔断开关，重建过滤器链决定选点时挂不挂熔断器。 */
    public void applyCircuitBreakerEnabled(boolean enabled) {
        filterSettings.getCircuitBreaker().setEnabled(enabled);
        rebuildFilters();
        logCircuitBreaker("开关");
    }

    public void applyCircuitBreakerFailureThreshold(int failureThreshold) {
        filterSettings.getCircuitBreaker().setFailureThreshold(failureThreshold);
        logCircuitBreaker("连续失败阈值");
    }

    public void applyCircuitBreakerOpenSeconds(int openSeconds) {
        filterSettings.getCircuitBreaker().setOpenSeconds(openSeconds);
        logCircuitBreaker("打开秒数");
    }

    public void applyCircuitBreakerRecovery(String recovery) {
        filterSettings.getCircuitBreaker().setRecovery(recovery);
        logCircuitBreaker("恢复策略");
    }

    /** 热更新换台重试开关。过滤器握着同一份 settings，不用重建链。 */
    public void applyRetryEnabled(boolean enabled) {
        filterSettings.getRetry().setEnabled(enabled);
        log.info("内置换台重试已热更新: enabled={}", enabled);
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

    /** 写入尝试的终态。四种终态互斥且穷尽：要么生效，要么三种「没生效」之一。 */
    public enum OperationStatus {
        /** 已生效并落盘。 */
        APPLIED,
        /** 乐观锁冲突，运行态与磁盘都没动。重读版本后重提。 */
        CONFLICT,
        /** 校验不通过，运行态与磁盘都没动。得改请求本身。 */
        REJECTED,
        /** 校验通过但落盘失败，运行态与磁盘都没动。修好磁盘后用同一 operationId 重试即可。 */
        FAILED
    }

    /**
     * 一条操作记录。
     *
     * @param operationId 调用方给的幂等 ID
     * @param status      终态
     * @param revision    该操作对应的版本；冲突时为「当前」版本，便于调用方刷新后重试
     * @param message     人类可读说明
     * @param atMillis    记录时刻
     */
    public record OperationRecord(String operationId, OperationStatus status, int revision, String message,
                                  long atMillis) { }

    /**
     * 一次变更的结果。
     *
     * @param status      {@code APPLIED} 或 {@code REPLAYED}（幂等重放，未再次生效）
     * @param revision    生效后的版本号
     * @param operationId 产生该版本的操作 ID
     * @param message     人类可读说明
     * @param routes      生效后的路由表
     */
    public record RouteChangeResult(String status, int revision, String operationId, String message,
                                    List<RouteConfig> routes) { }

    /** 带版本号的路由快照。 */
    private record VersionedRoutes(int revision, List<RouteConfig> routes) { }

    /** @return 操作记录；未知操作返回 null */
    public synchronized OperationRecord operationOf(String operationId) {
        return operationId == null ? null : operations.get(operationId);
    }

    /**
     * 启动时用 overlay 里的版本元信息对齐内存版本号。
     *
     * 路由内容由调用方决定（Admin 关闭时只认 YAML），这里只认版本号，
     * 于是「重启后报出的版本」就是「重启前确认过的版本」。
     *
     * @param revision    已确认版本号
     * @param operationId 产生该版本的操作 ID，可为空
     */
    public synchronized void restoreRoutesRevision(int revision, String operationId) {
        if (revision < 0) {
            return;
        }
        this.revision = revision;
        this.appliedOperationId = operationId == null ? "" : operationId;
        history.clear();
        history.addLast(new VersionedRoutes(revision, getRouteMatcher().listRoutes()));
        log.info("路由版本已从 overlay 恢复: revision={}, operationId={}", revision, this.appliedOperationId);
    }

    /**
     * 预览候选路由表的差异：只做校验与比对，不落盘、不生效。
     *
     * @param candidate 候选路由表
     * @return 逐条差异；候选与当前完全一致时为空列表
     * @throws IllegalArgumentException 候选路由校验失败
     */
    public List<RouteDiff.Change> previewRoutes(List<RouteConfig> candidate) {
        List<RouteConfig> normalized = routeValidator.normalizeAndValidate(candidate);
        return RouteDiff.between(getRouteMatcher().listRoutes(), normalized);
    }

    /**
     * 整表替换路由：幂等重放 → 乐观锁 → 校验 → 预构建 → 先落盘 → 原子替换 → 记账。
     *
     * 顺序是刻意的：所有「可能失败的步骤」都在落盘之前，落盘之后只剩不会失败的原子引用替换，
     * 于是「写盘成功但内存没换」这个窗口被结构性消除，而不是靠 try/catch 兜。
     *
     * @param expectedRevision 调用方以为的当前版本；与真实值不符直接冲突
     * @param operationId      幂等 ID；重复提交同一个 ID 会返回上次结果而不再次生效
     * @param routes           候选路由表
     * @return 变更结果
     * @throws ManageApiException 版本冲突（409）或校验失败（400）
     */
    public synchronized RouteChangeResult applyRoutes(int expectedRevision, String operationId,
                                                     List<RouteConfig> routes) {
        return change(expectedRevision, operationId, routes, "路由已热更新并落盘");
    }

    /**
     * 回滚到最近某次已应用的快照：走同一条变更协议，因此「回滚不会覆盖别人的新修改」由乐观锁天然保证。
     *
     * 局限：只覆盖最近 {@value #MAX_APPLIED_HISTORY} 次已应用快照，且重启后窗口清空（当前版本仍在）。
     *
     * @param expectedRevision 调用方以为的当前版本
     * @param operationId      幂等 ID
     * @param toRevision       目标版本号
     * @return 变更结果
     * @throws ManageApiException 版本冲突、目标版本不在窗口内
     */
    public synchronized RouteChangeResult rollback(int expectedRevision, String operationId, int toRevision) {
        RouteChangeResult replayed = replayOf(operationId);
        if (replayed != null) {
            return replayed;
        }
        List<RouteConfig> snapshot = routesAt(toRevision);
        if (snapshot == null) {
            throw new ManageApiException(HttpResponseStatus.BAD_REQUEST,
                    "回滚目标版本不在最近 " + MAX_APPLIED_HISTORY + " 次已应用快照内: " + toRevision, Map.of());
        }
        return change(expectedRevision, operationId, snapshot, "已回滚到版本 " + toRevision);
    }

    /** 统一变更协议。 */
    private RouteChangeResult change(int expectedRevision, String operationId, List<RouteConfig> routes,
                                     String message) {
        RouteChangeResult replayed = replayOf(operationId);
        if (replayed != null) {
            return replayed;
        }
        if (expectedRevision != revision) {
            String conflict = "版本冲突：期望 " + expectedRevision + "，当前 " + revision;
            recordOperation(operationId, OperationStatus.CONFLICT, revision, conflict);
            throw new ManageApiException(HttpResponseStatus.CONFLICT, conflict,
                    Map.of("currentRevision", revision));
        }

        List<RouteConfig> normalized;
        try {
            normalized = routeValidator.normalizeAndValidate(routes);
        } catch (IllegalArgumentException ex) {
            recordOperation(operationId, OperationStatus.REJECTED, revision, ex.getMessage());
            throw ex;
        }

        // 预构建：可运行态在这里一次做完，落盘之后不再有会失败的构建
        RouteMatcher nextMatcher = new RouteMatcher(normalized);
        List<Filter> nextFilters = assembleFilters(nextMatcher);

        int nextRevision = revision + 1;
        // 先落盘：失败则抛异常，运行态与磁盘都还是旧版本，两者一致
        try {
            routeOverlayStore.save(nextRevision, operationId, System.currentTimeMillis(), normalized);
        } catch (RuntimeException ex) {
            // 落盘失败也必须留痕。这是四种终态里唯一「状态没变、但最需要事后可查」的一种：
            // 不留痕的话，调用方丢了响应再来查只会看到 UNKNOWN，无法区分
            // 「提交过但没写进去」和「压根没提交」，也就无从判断该不该重试。
            // 记的是当前版本（本操作没有产生新版本），与 CONFLICT 的口径一致。
            recordOperation(operationId, OperationStatus.FAILED, revision, ex.getMessage());
            throw ex;
        }

        // 落盘成功，下面只有不会失败的赋值
        StaticUpstreamCluster.rebuild(normalized);
        routeMatcherRef.set(nextMatcher);
        configManager.replacePluginConfigs(configurablePlugins(nextFilters));
        filters.set(nextFilters);
        watchServices(normalized);

        revision = nextRevision;
        appliedOperationId = operationId == null ? "" : operationId;
        pushHistory(new VersionedRoutes(nextRevision, normalized));
        recordOperation(operationId, OperationStatus.APPLIED, nextRevision, message);
        log.info("路由已热更新, count={}, revision={}, operationId={}, overlay={}",
                normalized.size(), nextRevision, appliedOperationId, routeOverlayStore.getPath().toAbsolutePath());
        return new RouteChangeResult("APPLIED", nextRevision, appliedOperationId, message, normalized);
    }

    /** 新增或按 businessPrefix/id 替换单条路由，整表走同一条变更协议。 */
    public synchronized RouteChangeResult addOrReplaceRoute(int expectedRevision, String operationId,
                                                           RouteConfig route) {
        RouteChangeResult replayed = replayOf(operationId);
        if (replayed != null) {
            return replayed;
        }
        List<RouteConfig> current = new ArrayList<>(getRouteMatcher().listRoutes());
        String prefix = route.getBusinessPrefix();
        current.removeIf(item -> prefix != null && prefix.equals(item.getBusinessPrefix()));
        if (route.getId() != null && !route.getId().isBlank()) {
            current.removeIf(item -> route.getId().equals(item.getId()));
        }
        current.add(route);
        return change(expectedRevision, operationId, current, "路由已新增/更新并热生效");
    }

    /** 按 id 或 businessPrefix 删除路由，整表走同一条变更协议。 */
    public synchronized RouteChangeResult removeRoute(int expectedRevision, String operationId, String idOrPrefix) {
        RouteChangeResult replayed = replayOf(operationId);
        if (replayed != null) {
            return replayed;
        }
        if (idOrPrefix == null || idOrPrefix.isBlank()) {
            throw new IllegalArgumentException("删除路由需要 id 或 businessPrefix");
        }
        List<RouteConfig> current = new ArrayList<>(getRouteMatcher().listRoutes());
        boolean removed = current.removeIf(item ->
                idOrPrefix.equals(item.getId()) || idOrPrefix.equals(item.getBusinessPrefix()));
        if (!removed) {
            throw new IllegalArgumentException("未找到路由: " + idOrPrefix);
        }
        return change(expectedRevision, operationId, current, "路由已删除并热生效");
    }

    /** 已 APPLIED 的操作直接返回既有结果，不再生效一次。 */
    private RouteChangeResult replayOf(String operationId) {
        if (operationId == null || operationId.isBlank()) {
            return null;
        }
        OperationRecord record = operations.get(operationId);
        if (record == null || record.status() != OperationStatus.APPLIED) {
            return null;
        }
        return new RouteChangeResult("REPLAYED", record.revision(), record.operationId(),
                "该操作已执行过，返回既有结果", getRouteMatcher().listRoutes());
    }

    private void recordOperation(String operationId, OperationStatus status, int recordRevision, String message) {
        if (operationId == null || operationId.isBlank()) {
            return;
        }
        operations.put(operationId,
                new OperationRecord(operationId, status, recordRevision, message, System.currentTimeMillis()));
    }

    private void pushHistory(VersionedRoutes snapshot) {
        history.addLast(snapshot);
        while (history.size() > MAX_APPLIED_HISTORY) {
            history.removeFirst();
        }
    }

    private List<RouteConfig> routesAt(int target) {
        for (VersionedRoutes entry : history) {
            if (entry.revision() == target) {
                return entry.routes();
            }
        }
        return null;
    }

    /** 动态发现模式下，对路由里每个版本目标补订 watch。 */
    private void watchServices(List<RouteConfig> routes) {
        if (!discoveryType.usesServiceDiscovery() || serviceDiscovery == null) {
            return;
        }
        for (RouteConfig route : routes) {
            if (route.getTargets() == null) {
                continue;
            }
            for (RouteTarget target : route.getTargets()) {
                serviceDiscovery.ensureWatch(target.serviceName(), target.group());
            }
        }
    }

    /** 限流配置是一个整体，任一字段变化都替换对应 Filter，避免运行中混用旧算法和新参数。 */
    private synchronized void updateRateLimit(Runnable update) {
        update.run();
        rebuildFilters();
        RateLimitSettings settings = filterSettings.getRateLimit();
        log.info("内置限流已热更新: enabled={}, algorithm={}, key={}",
                settings.isEnabled(), settings.getAlgorithm(), settings.getKey());
    }

    private void logCircuitBreaker(String changed) {
        CircuitBreakerSettings settings = filterSettings.getCircuitBreaker();
        log.info("内置熔断已热更新({}): enabled={}, failureThreshold={}, openSeconds={}, recovery={}",
                changed,
                settings.isEnabled(),
                settings.getFailureThreshold(),
                settings.getOpenSeconds(),
                settings.getRecovery());
    }

    /**
     * 按给定路由匹配器组装一条过滤器链；不替换运行态。
     *
     * 拆出来是为了让路由变更能在**落盘之前**把新链构建好——构建是会失败的（加载插件等），
     * 把它提前，落盘之后就不再有会失败的动作。
     */
    private List<Filter> assembleFilters(RouteMatcher matcher) {
        return assembler.assemble(
                filterSettings,
                matcher,
                proxyClient,
                discoveryType,
                serviceDiscovery,
                loadBalancer.get(),
                metricsRegistry,
                filterSettings.getCircuitBreaker().isEnabled() ? circuitBreaker : null);
    }

    /** 按当前路由、发现、LB、Filter 配置重新组装过滤器链并原子替换。 */
    private void rebuildFilters() {
        List<Filter> assembled = assembleFilters(routeMatcherRef.get());
        configManager.replacePluginConfigs(configurablePlugins(assembled));
        filters.set(assembled);
    }

    /** 收集当前真正参与请求链路的可配置插件；未启用的插件不会暴露给 Admin。 */
    private List<ConfigurablePlugin> configurablePlugins(List<Filter> assembled) {
        List<ConfigurablePlugin> plugins = new ArrayList<>();
        for (Filter filter : assembled) {
            if (filter instanceof ConfigurablePlugin configurable) {
                plugins.add(configurable);
            }
        }
        LoadBalancer current = loadBalancer.get();
        if (current instanceof ConfigurablePlugin configurable) {
            plugins.add(configurable);
        }
        return plugins;
    }

    /** 释放运行时持有的插件资源和出站连接池。 */
    public void close() {
        assembler.close();
        LoadBalancerFactory.shutdown();
        proxyClient.close();
    }
}
