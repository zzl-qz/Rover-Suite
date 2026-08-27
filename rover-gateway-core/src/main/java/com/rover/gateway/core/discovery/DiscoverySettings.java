package com.rover.gateway.core.discovery;

import com.rover.common.constants.NameserverConstants;
import com.rover.common.util.ServiceKeys;
import com.rover.gateway.core.config.GatewayDefaults;
import com.rover.gateway.core.route.RouteConfig;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Data;

/**
 * Author: Daylight
 * Created: 2026-08-10 16:20:00
 * Description: Gateway 服务发现配置
 */
@Data
public class DiscoverySettings {

    /** 发现模式，默认 STATIC */
    private DiscoveryType type = DiscoveryType.STATIC;

    /** Nameserver host，仅 NAMESERVER 模式使用 */
    private String nameserverHost = NameserverConstants.DEFAULT_HOST;

    /** Nameserver 端口，仅 NAMESERVER 模式使用 */
    private int nameserverPort = NameserverConstants.DEFAULT_PORT;

    /** 连接 Nameserver 订阅/查询时携带的协议 token；空表示不鉴权 */
    private String nameserverToken;

    /** 定时对账间隔（毫秒），防止订阅推送丢失 */
    private long reconcileIntervalMs = GatewayDefaults.RECONCILE_INTERVAL_MILLIS;

    /** 启动时需要订阅的服务列表；路由热更新时也会动态追加 */
    private List<ServiceSubscribeSpec> subscribeServices = new ArrayList<>();

    /** 注册中心专属配置；由具体 adapter 解释，核心不感知 Nacos 等供应商字段。 */
    private Map<String, String> providerProperties = new LinkedHashMap<>();

    /** 单个服务的订阅规格。 */
    @Data
    public static class ServiceSubscribeSpec {

        /** 服务名 */
        private String serviceName;

        /** 分组，可为 null 表示默认组 */
        private String group;
    }

    /** 默认静态发现，启动兜底和 Loader 共用这一份。 */
    public static DiscoverySettings staticDefaults() {
        DiscoverySettings settings = new DiscoverySettings();
        settings.setType(DiscoveryType.STATIC);
        return settings;
    }

    /** 从最终路由表抽订阅列表，启动时设一次即可。 */
    public static List<ServiceSubscribeSpec> subscribeSpecsFrom(List<RouteConfig> routes) {
        if (routes == null || routes.isEmpty()) {
            return new ArrayList<>();
        }
        Map<String, ServiceSubscribeSpec> unique = new LinkedHashMap<>();
        for (RouteConfig route : routes) {
            if (route == null || route.getServiceName() == null || route.getServiceName().isBlank()) {
                continue;
            }
            String key = ServiceKeys.serviceGroup(route.getServiceName(), route.getGroup());
            ServiceSubscribeSpec spec = new ServiceSubscribeSpec();
            spec.setServiceName(route.getServiceName());
            spec.setGroup(route.getGroup());
            unique.putIfAbsent(key, spec);
        }
        return new ArrayList<>(unique.values());
    }
}
