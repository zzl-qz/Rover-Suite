package com.rover.common.spi.loadbalance;

import com.rover.common.model.ServiceInstance;

/**
 * Author: Daylight
 * Created: 2026-08-13
 * Description: 网关负载均衡 SPI（内置算法 + plugins 自定义）
 *
 * 自定义场景示例：从 Redis 取用户分片键，再哈希到某台机器。
 * jar 放到 plugins，并写 META-INF/services/com.rover.common.spi.loadbalance.LoadBalancer。
 */
public interface LoadBalancer {

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

    /** 请求开始占用实例（最少连接用）；默认空实现 */
    default void onStart(ServiceInstance instance) {
    }

    /** 请求结束释放实例（最少连接用）；默认空实现 */
    default void onComplete(ServiceInstance instance) {
    }
}
