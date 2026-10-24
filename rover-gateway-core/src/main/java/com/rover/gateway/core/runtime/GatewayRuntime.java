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
import com.rover.gateway.core.route.RouteOperationStore;
import com.rover.common.json.JsonCodec;
import com.rover.gateway.core.route.RouteTarget;
import com.rover.gateway.core.route.RouteValidator;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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

    /** Route-scoped instance isolation overlay, durable across Gateway restarts. */
    private final com.rover.gateway.core.route.RouteInstanceIsolationStore instanceIsolationStore;

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

    /** 已确认版本号，与落盘 overlay 上的 revision 一致；只在 synchronized 内改。 */
    private int revision;

    /** 产生当前版本的操作 ID；空表示当前版本不是由管理口写入的。 */
    private String appliedOperationId = "";

    /** 最近已应用快照，供回滚；重启后清空（当前版本仍在，只是更早的快照没了）。 */
    @Getter(AccessLevel.NONE)
    private final Deque<VersionedRoutes> history = new ArrayDeque<>();

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
        try {
            this.instanceIsolationStore = new com.rover.gateway.core.route.RouteInstanceIsolationStore(routeOverlayStore.getPath());
        } catch (RuntimeException ex) {
            throw new IllegalStateException("Gateway 实例隔离持久存储不可用，拒绝启动以避免忽略已隔离实例", ex);
        }
        this.routeValidator = new RouteValidator(this.discoveryType);
        this.metricsRegistry.setUpstreamInFlightSupplier(proxyClient::getInFlightCount);
        this.metricsRegistry.setDiscoveryStatusSupplier(() -> serviceDiscovery instanceof ServiceDiscoveryStatus status
                ? status.status() : Map.of("supported", false));
        String pluginDir = this.filterSettings.getPluginDir();
        this.loadBalancer.set(LoadBalancerFactory.create(LoadBalancer.ROUND_ROBIN, pluginDir));
        this.loadBalanceStrategy.set(LoadBalancer.ROUND_ROBIN);
        List<RouteConfig> initialRoutes = routeValidator.normalizeAndValidate(
                routes == null ? List.of() : routes);
        assignIdentities(initialRoutes, List.of(), true);
        this.routeMatcherRef.set(new RouteMatcher(initialRoutes));
        StaticUpstreamCluster.rebuild(initialRoutes);
        // 指标按版本归因：注入配置声明的版本目标，供「声明版本 vs 观测版本」核对
        this.metricsRegistry.setRouteTargetsSupplier(this::declaredTargetsOf);
        // 0 号版本先入历史，回滚到「当前版本」于是也有据可依
        history.addLast(new VersionedRoutes(0, initialRoutes));
        rebuildFilters();
    }

    public synchronized void initializeManagedState() {
        routeOverlayStore.operations().initialize(currentState());
    }

    public com.rover.gateway.core.route.RouteInstanceIsolationStore getInstanceIsolationStore() {
        return instanceIsolationStore;
    }

    public DiscoveryType getDiscoveryType() { return discoveryType; }

    public ServiceDiscovery getServiceDiscovery() { return serviceDiscovery; }

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
        /** 经可信回执确认未生效的失败；存储不可用本身不允许伪造此状态。 */
        FAILED,
        /** 完整回执已过保留期；操作号和摘要仍永久保留，禁止再次执行。 */
        EXPIRED,
        /** 旧 overlay 不含可证明的完整回执。 */
        UNKNOWN
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
                                  long atMillis, String requestHash, long expiresAtMillis) { }

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
        RouteOperationStore.Receipt receipt = operationId == null ? null : routeOverlayStore.operations().find(operationId);
        if (receipt == null) { return null; }
        boolean expired = receipt.expired(routeOverlayStore.operations().now());
        return new OperationRecord(receipt.operationId(), expired ? OperationStatus.EXPIRED
                : OperationStatus.valueOf(receipt.status()), receipt.revision(), expired
                ? "回执已过期；不能据此断言未执行，操作号禁止复用" : receipt.message(),
                receipt.atMillis(), receipt.requestHash(), receipt.expiresAtMillis());
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
     * 候选运行态先构建，配置与回执同事务提交后发布。提交后进程崩溃由持久状态恢复；
     * 提交响应未知必须按原操作号对账，不能推断「未执行」。
     *
     * @param expectedRevision 调用方以为的当前版本；与真实值不符直接冲突
     * @param operationId      幂等 ID；重复提交同一个 ID 会返回上次结果而不再次生效
     * @param routes           候选路由表
     * @return 变更结果
     * @throws ManageApiException 版本冲突（409）或校验失败（400）
     */
    public synchronized RouteChangeResult applyRoutes(int expectedRevision, String operationId,
                                                     List<RouteConfig> routes) {
        String hash = requestHash("REPLACE_ROUTES", expectedRevision, RouteOverlayStore.toRows(routes));
        return change(expectedRevision, operationId, hash, routes, "路由已热更新并落盘");
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
        String hash = requestHash("ROLLBACK", expectedRevision, toRevision);
        RouteChangeResult replayed = replayOf(operationId, hash);
        if (replayed != null) {
            return replayed;
        }
        List<RouteConfig> snapshot = routesAt(toRevision);
        if (snapshot == null) {
            recordRejection(operationId, hash, OperationStatus.REJECTED, "回滚目标版本不在历史窗口内: " + toRevision);
            throw new ManageApiException(HttpResponseStatus.BAD_REQUEST,
                    "回滚目标版本不在最近 " + MAX_APPLIED_HISTORY + " 次已应用快照内: " + toRevision, Map.of());
        }
        return change(expectedRevision, operationId, hash, snapshot, "已回滚到版本 " + toRevision);
    }

    /** 统一变更协议。 */
    private RouteChangeResult change(int expectedRevision, String operationId, String requestHash, List<RouteConfig> routes,
                                     String message) {
        RouteChangeResult replayed = replayOf(operationId, requestHash);
        if (replayed != null) {
            return replayed;
        }
        if (expectedRevision != revision) {
            String conflict = "版本冲突：期望 " + expectedRevision + "，当前 " + revision;
            recordRejection(operationId, requestHash, OperationStatus.CONFLICT, conflict);
            throw new ManageApiException(HttpResponseStatus.CONFLICT, conflict,
                    Map.of("currentRevision", revision));
        }

        List<RouteConfig> normalized;
        try {
            normalized = routeValidator.normalizeAndValidate(routes);
        } catch (IllegalArgumentException ex) {
            recordRejection(operationId, requestHash, OperationStatus.REJECTED, ex.getMessage());
            throw ex;
        }

        // 预构建：可运行态在这里一次做完，落盘之后不再有会失败的构建
        assignIdentities(normalized, getRouteMatcher().listRoutes(), false);
        rejectInvalidatedIsolations(normalized);
        RouteMatcher nextMatcher = new RouteMatcher(normalized);
        List<Filter> nextFilters = assembleFilters(nextMatcher);

        int nextRevision = revision + 1;
        long now = routeOverlayStore.operations().now();
        routeOverlayStore.operations().commit(currentState(),
                new RouteOverlayStore.AppliedState(nextRevision, operationId, now, normalized),
                receipt(operationId, requestHash, OperationStatus.APPLIED, nextRevision, message, now, normalized));

        // 提交后的发布窗口若崩溃，重启以事务库恢复；回执查询不能依赖这些内存赋值。
        StaticUpstreamCluster.rebuild(normalized);
        routeMatcherRef.set(nextMatcher);
        configManager.replacePluginConfigs(configurablePlugins(nextFilters));
        filters.set(nextFilters);
        watchServices(normalized);

        revision = nextRevision;
        appliedOperationId = operationId == null ? "" : operationId;
        pushHistory(new VersionedRoutes(nextRevision, normalized));
        log.info("路由已热更新, count={}, revision={}, operationId={}, overlay={}",
                normalized.size(), nextRevision, appliedOperationId, routeOverlayStore.getPath().toAbsolutePath());
        return new RouteChangeResult("APPLIED", nextRevision, appliedOperationId, message, normalized);
    }

    /** Route edits and isolation writes share this monitor so validation cannot race a route replacement. */
    public synchronized void withRouteMutationLock(Runnable operation) {
        operation.run();
    }

    private void rejectInvalidatedIsolations(List<RouteConfig> candidate) {
        for (var isolation : instanceIsolationStore.list()) {
            if (!isolation.active()) { continue; }
            var key = isolation.key();
            boolean retained = candidate.stream().anyMatch(route ->
                    key.routeUid().equals(route.getResourceUid())
                            && key.generation() == route.getGeneration()
                            && key.routeId().equals(route.getId())
                            && route.getTargets() != null
                            && route.getTargets().stream().anyMatch(target ->
                                    key.serviceName().equals(target.serviceName())
                                            && (target.group() == null || target.group().isBlank()
                                                    || key.group().equals(target.group()))));
            if (!retained) {
                throw new ManageApiException(HttpResponseStatus.CONFLICT,
                        "活动实例隔离期间不能删除或修改对应路由；先恢复实例流量，再重试",
                        Map.of("code", "ACTIVE_INSTANCE_ISOLATION", "routeId", key.routeId(),
                                "instanceId", key.instanceId()));
            }
        }
    }

    /** 新增或按 businessPrefix/id 替换单条路由，整表走同一条变更协议。 */
    public synchronized RouteChangeResult addOrReplaceRoute(int expectedRevision, String operationId,
                                                           RouteConfig route) {
        String hash = requestHash("UPSERT_ROUTE", expectedRevision, RouteOverlayStore.toRow(route));
        RouteChangeResult replayed = replayOf(operationId, hash);
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
        return change(expectedRevision, operationId, hash, current, "路由已新增/更新并热生效");
    }

    /** 按 id 或 businessPrefix 删除路由，整表走同一条变更协议。 */
    public synchronized RouteChangeResult removeRoute(int expectedRevision, String operationId, String idOrPrefix) {
        String hash = requestHash("DELETE_ROUTE", expectedRevision, idOrPrefix == null ? "" : idOrPrefix);
        RouteChangeResult replayed = replayOf(operationId, hash);
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
            recordRejection(operationId, hash, OperationStatus.REJECTED, "未找到路由: " + idOrPrefix);
            throw new IllegalArgumentException("未找到路由: " + idOrPrefix);
        }
        return change(expectedRevision, operationId, hash, current, "路由已删除并热生效");
    }

    /** 回执查询先于当前资源定位：后续删除/改名不能破坏原操作的重放。 */
    private RouteChangeResult replayOf(String operationId, String requestHash) {
        if (operationId == null || operationId.isBlank() || operationId.length() > 200) {
            throw new IllegalArgumentException("operationId 必须为 1~200 字符");
        }
        RouteOperationStore.Receipt record = routeOverlayStore.operations().find(operationId);
        if (record == null) {
            if (operationId.equals(appliedOperationId)) {
                throw new ManageApiException(HttpResponseStatus.CONFLICT,
                        "旧 overlay 只有最新操作号，没有参数摘要；不能证明重试安全，请人工对账",
                        Map.of("code", "LEGACY_OPERATION_UNKNOWN", "currentRevision", revision));
            }
            return null;
        }
        if (!record.requestHash().equals(requestHash)) {
            throw new ManageApiException(HttpResponseStatus.CONFLICT, "同一 operationId 的请求参数不一致",
                    Map.of("code", "OPERATION_PARAMETER_MISMATCH", "currentRevision", revision));
        }
        if (record.expired(routeOverlayStore.operations().now())) {
            throw new ManageApiException(HttpResponseStatus.CONFLICT, "操作回执已过期，禁止再次执行；请人工对账",
                    Map.of("code", "OPERATION_EXPIRED", "currentRevision", revision));
        }
        if (!"APPLIED".equals(record.status())) {
            throw new ManageApiException("CONFLICT".equals(record.status()) ? HttpResponseStatus.CONFLICT
                    : HttpResponseStatus.BAD_REQUEST, record.message(), Map.of("currentRevision", revision,
                    "operationStatus", record.status(), "requestHash", record.requestHash()));
        }
        return new RouteChangeResult("REPLAYED", record.revision(), record.operationId(),
                "该操作已执行过，返回原始结果；当前状态请重新读取路由", record.routes());
    }

    private static String requestHash(String type, int expectedRevision, Object parameters) {
        if (expectedRevision < 0) { throw new IllegalArgumentException("revision 不能为负数"); }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    JsonCodec.toJson(List.of("route-operation-v1", type, expectedRevision, parameters))
                            .getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private RouteOverlayStore.AppliedState currentState() {
        return new RouteOverlayStore.AppliedState(revision, appliedOperationId, 0, getRouteMatcher().listRoutes());
    }

    private RouteOperationStore.Receipt receipt(String operationId, String hash, OperationStatus status,
                                               int resultRevision, String message, long now, List<RouteConfig> routes) {
        return new RouteOperationStore.Receipt(operationId, hash, status.name(), resultRevision, message,
                now, Math.addExact(now, RouteOperationStore.RECEIPT_RETENTION_MILLIS), routes);
    }

    private void recordRejection(String operationId, String hash, OperationStatus status, String message) {
        long now = routeOverlayStore.operations().now();
        routeOverlayStore.operations().commit(currentState(), currentState(),
                receipt(operationId, hash, status, revision, message, now, List.of()));
    }

    /** 单目标调权在同一把锁内定位和提交，摘要绑定输入意图，不绑定后来变化的整张路由表。 */
    public synchronized RouteChangeResult adjustTargetWeight(int expectedRevision, String operationId,
                                                            String routeKey, String serviceName, String group, int weight) {
        return adjustTargetWeight(expectedRevision, operationId, routeKey, serviceName, group, weight, "", 0);
    }

    public synchronized RouteChangeResult adjustTargetWeight(int expectedRevision, String operationId,
                                                            String routeKey, String serviceName, String group, int weight,
                                                            String expectedResourceUid, long expectedGeneration) {
        boolean bound = expectedResourceUid != null && !expectedResourceUid.isBlank();
        String hash = bound ? requestHash("TARGET_WEIGHT_BOUND", expectedRevision,
                List.of(routeKey, serviceName, group, weight, expectedResourceUid, expectedGeneration))
                : requestHash("TARGET_WEIGHT", expectedRevision, List.of(routeKey, serviceName, group, weight));
        RouteChangeResult replayed = replayOf(operationId, hash);
        if (replayed != null) { return replayed; }
        if (routeKey.isBlank() || serviceName.isBlank() || weight < 0 || weight > RouteTarget.MAX_WEIGHT) {
            throw new IllegalArgumentException("routeId、serviceName 必填，weight 必须在合法范围内");
        }
        List<RouteConfig> current = new ArrayList<>(getRouteMatcher().listRoutes());
        boolean matched = false;
        for (int i = 0; i < current.size(); i++) {
            RouteConfig route = current.get(i);
            if (!routeKey.equals(route.getId()) && !routeKey.equals(route.getBusinessPrefix())) { continue; }
            if (bound && (!expectedResourceUid.equals(route.getResourceUid()) || expectedGeneration != route.getGeneration())) {
                String message = "资源 UID/代次已改变，禁止旧计划修改同名新资源";
                recordRejection(operationId, hash, OperationStatus.CONFLICT, message);
                throw new ManageApiException(HttpResponseStatus.CONFLICT, message,
                        Map.of("code", "RESOURCE_IDENTITY_MISMATCH", "currentRevision", revision));
            }
            List<RouteTarget> targets = new ArrayList<>();
            for (RouteTarget target : route.getTargets()) {
                if (serviceName.equals(target.serviceName()) && group.equals(target.group() == null ? "" : target.group())) {
                    targets.add(new RouteTarget(serviceName, target.group(), weight));
                    matched = true;
                } else { targets.add(target); }
            }
            RouteConfig copy = RouteValidator.copyRoute(route);
            copy.setTargets(targets);
            current.set(i, copy);
        }
        if (!matched) {
            String message = "未找到版本目标: " + routeKey + "/" + serviceName + "/" + group;
            recordRejection(operationId, hash, OperationStatus.REJECTED, message);
            throw new IllegalArgumentException(message);
        }
        return change(expectedRevision, operationId, hash, current, "目标权重已更新并落盘");
    }

    private void pushHistory(VersionedRoutes snapshot) {
        history.addLast(snapshot);
        while (history.size() > MAX_APPLIED_HISTORY) {
            history.removeFirst();
        }
    }

    /** 同 ID 编辑保留当前身份；删除后重建生成新 UID，禁止客户端复用历史 UID。 */
    private static void assignIdentities(List<RouteConfig> routes, List<RouteConfig> current, boolean restoring) {
        for (RouteConfig route : routes) {
            RouteConfig previous = current.stream().filter(existing ->
                    existing.getId() != null ? existing.getId().equals(route.getId())
                            : existing.getBusinessPrefix().equals(route.getBusinessPrefix())).findFirst().orElse(null);
            if (previous != null) {
                route.setResourceUid(previous.getResourceUid());
                boolean unchanged = RouteOverlayStore.toRow(previous).equals(RouteOverlayStore.toRow(route));
                route.setGeneration(unchanged ? previous.getGeneration() : Math.addExact(previous.getGeneration(), 1));
            } else if (!restoring || route.getResourceUid() == null || route.getResourceUid().isBlank()) {
                route.setResourceUid(java.util.UUID.randomUUID().toString());
                route.setGeneration(1);
            }
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
                filterSettings.getCircuitBreaker().isEnabled() ? circuitBreaker : null,
                instanceIsolationStore);
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
