package com.rover.gateway.bootstrap.config;

import com.rover.common.constants.NameserverConstants;
import com.rover.common.plugin.PluginSpiLoader;
import com.rover.common.spi.loadbalance.LoadBalancer;
import com.rover.gateway.core.config.GatewayDefaults;
import com.rover.gateway.core.discovery.DiscoverySettings;
import com.rover.gateway.core.discovery.DiscoveryType;
import com.rover.gateway.core.filter.FilterSettings;
import com.rover.gateway.core.filter.ratelimit.RateLimitSettings;
import com.rover.gateway.core.route.RouteConfig;
import com.rover.gateway.core.server.CorsSettings;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * Author: Daylight
 * Created: 2026-08-08 15:13:00
 * Description: Gateway 启动配置（端口、发现模式、路由等）
 */
@Data
public class GatewayConfig {


    private RoverProperties rover = new RoverProperties();

    public int getPortOrDefault() {
        return gatewayProperties().getPort();
    }

    public String getBindHostOrDefault() {
        String bindHost = gatewayProperties().getServer().getBindHost();
        return bindHost == null || bindHost.isBlank() ? GatewayDefaults.BIND_HOST : bindHost.trim();
    }

    public String getAdminTokenOrDefault() {
        return gatewayProperties().getAdminToken();
    }

    /** 是否启用 Admin 管理面；关闭时只使用 YAML 启动配置。 */
    public boolean isAdminEnabled() {
        return gatewayProperties().isAdminEnabled();
    }

    public int getMaxContentLengthBytesOrDefault() {
        return gatewayProperties().getServer().getMaxContentLengthBytes();
    }

    public int getConnectTimeoutMillisOrDefault() {
        return gatewayProperties().getProxy().getConnectTimeoutMillis();
    }

    public int getRequestTimeoutMillisOrDefault() {
        return gatewayProperties().getProxy().getRequestTimeoutMillis();
    }

    public String getProxyOutboundOrDefault() {
        String outbound = gatewayProperties().getProxy().getOutbound();
        return outbound == null || outbound.isBlank() ? "netty" : outbound.trim();
    }

    public DiscoveryType getDiscoveryType() {
        return DiscoveryType.from(gatewayProperties().getDiscovery().getType());
    }

    public boolean isMetricsEnabled() {
        return gatewayProperties().getMetrics().isEnabled();
    }

    public int getMetricsWindowSecondsOrDefault() {
        int window = gatewayProperties().getMetrics().getWindowSeconds();
        return window <= 0 ? GatewayDefaults.METRICS_WINDOW_SECONDS : window;
    }

    public boolean isTraceEnabled() {
        return gatewayProperties().getTrace().isEnabled();
    }

    public long getTraceSlowThresholdMillisOrDefault() {
        long threshold = gatewayProperties().getTrace().getSlowThresholdMillis();
        return threshold <= 0 ? GatewayDefaults.TRACE_SLOW_THRESHOLD_MILLIS : threshold;
    }

    public double getTraceSampleRateOrDefault() {
        return gatewayProperties().getTrace().getSampleRate();
    }

    public boolean isDispatchOnEventLoop() {
        return gatewayProperties().getServer().isDispatchOnEventLoop();
    }

    public String getLoadBalanceStrategyOrDefault() {
        LoadBalanceProperties lb = gatewayProperties().getLoadbalance();
        if (lb == null || lb.getStrategy() == null || lb.getStrategy().isBlank()) {
            return LoadBalancer.ROUND_ROBIN;
        }
        return lb.getStrategy().trim();
    }

    /** 把 yml 的 cors 配置转成 core 的 CorsSettings。 */
    public CorsSettings toCorsSettings() {
        return GatewayConfigMapper.toCorsSettings(this);
    }

    public FilterSettings toFilterSettings() {
        return GatewayConfigMapper.toFilterSettings(this);
    }

    public void validate() {
        GatewayConfigValidator.validate(this);
    }

    /** 把配置的路由封装成 RouteConfig 集合。 */
    public List<RouteConfig> toRouteConfigs() {
        return GatewayConfigMapper.toRouteConfigs(this);
    }

    public DiscoverySettings toDiscoverySettings() {
        return GatewayConfigMapper.toDiscoverySettings(this);
    }

    GatewayProperties gatewayProperties() {
        if (rover == null) {
            rover = new RoverProperties();
        }
        if (rover.getGateway() == null) {
            rover.setGateway(new GatewayProperties());
        }
        GatewayProperties gateway = rover.getGateway();
        if (gateway.getServer() == null) {
            gateway.setServer(new ServerProperties());
        }
        if (gateway.getProxy() == null) {
            gateway.setProxy(new ProxyProperties());
        }
        if (gateway.getFilters() == null) {
            gateway.setFilters(new FilterProperties());
        }
        if (gateway.getRateLimit() == null) {
            gateway.setRateLimit(new RateLimitProperties());
        }
        if (gateway.getDiscovery() == null) {
            gateway.setDiscovery(new DiscoveryProperties());
        }
        if (gateway.getDiscovery().getNameserver() == null) {
            gateway.getDiscovery().setNameserver(new NameserverProperties());
        }
        if (gateway.getMetrics() == null) {
            gateway.setMetrics(new MetricsProperties());
        }
        if (gateway.getTrace() == null) {
            gateway.setTrace(new TraceProperties());
        }
        return gateway;
    }

    String resolveStripPrefix(RouteProperties route) {
        if (route.getStripPrefix() != null) {
            return route.getStripPrefix();
        }
        if (gatewayProperties().getRewrite() == null) {
            return null;
        }
        return gatewayProperties().getRewrite().getStripPrefix();
    }

    @Data
    public static class RoverProperties {
        private GatewayProperties gateway = new GatewayProperties();
    }

    @Data
    public static class GatewayProperties {
        private int port = GatewayDefaults.HTTP_PORT;
        /** 是否开放 Admin 管理面及其运行时覆盖，默认开启以保持兼容。 */
        private boolean adminEnabled = true;
        /** 管理口鉴权 token（/_manage/** 校验）；空表示不鉴权 */
        private String adminToken;
        private ServerProperties server = new ServerProperties();
        private ProxyProperties proxy = new ProxyProperties();
        private FilterProperties filters = new FilterProperties();
        private RateLimitProperties rateLimit = new RateLimitProperties();
        private LoadBalanceProperties loadbalance = new LoadBalanceProperties();
        private RewriteProperties rewrite = new RewriteProperties();
        private CorsProperties cors = new CorsProperties();
        private DiscoveryProperties discovery = new DiscoveryProperties();
        private MetricsProperties metrics = new MetricsProperties();
        private TraceProperties trace = new TraceProperties();
        private List<RouteProperties> routes = new ArrayList<>();
    }

    @Data
    public static class MetricsProperties {
        private boolean enabled = true;
        private int windowSeconds = GatewayDefaults.METRICS_WINDOW_SECONDS;
    }

    @Data
    public static class TraceProperties {
        private boolean enabled = true;
        private long slowThresholdMillis = GatewayDefaults.TRACE_SLOW_THRESHOLD_MILLIS;
        private double sampleRate = GatewayDefaults.TRACE_SAMPLE_RATE;
    }

    @Data
    public static class LoadBalanceProperties {
        /** round_robin / random / weighted_round_robin / ip_hash / least_connections / 自定义 */
        private String strategy = LoadBalancer.ROUND_ROBIN;
    }

    @Data
    public static class DiscoveryProperties {
        /** static | nameserver */
        private String type = DiscoveryType.STATIC.name();
        private NameserverProperties nameserver = new NameserverProperties();
    }

    @Data
    public static class NameserverProperties {
        private String address = NameserverConstants.DEFAULT_ADDRESS;
        private long reconcileIntervalMs = GatewayDefaults.RECONCILE_INTERVAL_MILLIS;
        /** 连接 Nameserver 订阅/查询时携带的协议 token；空表示不鉴权 */
        private String token;
    }

    @Data
    public static class FilterProperties {
        private boolean enabled = true;
        private String pluginDir = PluginSpiLoader.DEFAULT_DIR;
        private List<String> classes = new ArrayList<>();
        /** 是否装配访问日志过滤器；true 时打 debug，false 时不装 */
        private boolean accessLog = true;
    }

    @Data
    public static class RateLimitProperties {
        private boolean enabled = false;
        private String algorithm = RateLimitSettings.TOKEN_BUCKET;
        private String key = RateLimitSettings.PATH;
        private long permitsPerSecond = 1000;
        private long burst = 2000;
        private long limit = 1000;
        private int windowSeconds = 1;
    }

    @Data
    public static class ServerProperties {
        private int maxContentLengthBytes = GatewayDefaults.MAX_REQUEST_BODY_BYTES;
        /** 监听地址，默认 0.0.0.0；可收紧到本机/内网 */
        private String bindHost = GatewayDefaults.BIND_HOST;
        /**
         * 默认 false：Handler 走业务线程池，跟 EventLoop 收发包分开。
         * true 少一次 hop，但会占 I/O 线程；有阻塞插件必须保持 false。
         */
        private boolean dispatchOnEventLoop = false;
    }

    @Data
    public static class ProxyProperties {
        private int connectTimeoutMillis = GatewayDefaults.CONNECT_TIMEOUT_MILLIS;
        private int requestTimeoutMillis = GatewayDefaults.REQUEST_TIMEOUT_MILLIS;
        /** netty=出站走 Netty；jdk=第一版 JDK HttpClient，留给对照和回滚 */
        private String outbound = "netty";
    }

    @Data
    public static class RewriteProperties {
        private String stripPrefix;
    }

    @Data
    public static class CorsProperties {
        private boolean enabled = false;
        private List<String> allowedOrigins = new ArrayList<>();
        private List<String> allowedMethods = new ArrayList<>();
        private List<String> allowedHeaders = new ArrayList<>();
        private long maxAgeSeconds = GatewayDefaults.CORS_MAX_AGE_SECONDS;
        private boolean credentials = false;
    }

    @Data
    public static class RouteProperties {
        private String id;
        private String businessPrefix;
        private String targetUrl;
        /** 静态多上游，元素可写 http://host:port|weight */
        private List<String> targetUrls = new ArrayList<>();
        private String serviceName;
        private String group;
        private String stripPrefix;
    }
}
