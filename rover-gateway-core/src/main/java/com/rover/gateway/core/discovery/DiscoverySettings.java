package com.rover.gateway.core.discovery;

import com.rover.common.constants.NameserverConstants;
import com.rover.gateway.core.config.GatewayDefaults;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * Author: Daylight
 * Created: 2026-08-10 16:20:00
 * Description: Gateway 发现运行时配置
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

    /** 单个服务的订阅规格。 */
    @Data
    public static class ServiceSubscribeSpec {

        /** 服务名 */
        private String serviceName;

        /** 分组，可为 null 表示默认组 */
        private String group;
    }
}
