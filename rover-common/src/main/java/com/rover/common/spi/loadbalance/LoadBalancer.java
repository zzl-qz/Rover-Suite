package com.rover.common.spi.loadbalance;

import com.rover.common.model.ServiceInstance;

/**
 * Author: Daylight
 * Created: 2026-08-13 15:05:00
 * Description: 网关负载均衡 SPI（内置算法 + plugins 自定义，经 META-INF/services 注册）
 */
public interface LoadBalancer {

    /** 内置负载均衡策略名：轮询（也是默认策略） */
    String ROUND_ROBIN = BuiltinLoadBalanceStrategy.ROUND_ROBIN.configName();

    /**
     * 策略名，配置 gateway.loadbalance.strategy 用这个匹配。
     * 内置：round_robin / random / weighted_round_robin / ip_hash / least_connections
     */
    String name();

    /**
     * 从候选实例里选一台。
     *
     * @param context 集群键、实例列表、请求上下文
     * @return 选中实例；无可用时返回 null
     */
    ServiceInstance choose(LoadBalanceContext context);

    /**
     * @DL 扩展钩子：需要维护实例并发状态的算法可在请求开始时记账；默认空实现。
     */
    default void onStart(ServiceInstance instance) {
    }

    /**
     * @DL 扩展钩子：需要维护实例并发状态的算法可在请求结束时释放记账；默认空实现。
     */
    default void onComplete(ServiceInstance instance) {
    }
}
