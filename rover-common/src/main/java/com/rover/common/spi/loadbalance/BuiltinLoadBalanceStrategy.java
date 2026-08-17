package com.rover.common.spi.loadbalance;

import java.util.Arrays;
import java.util.List;

/** 内置负载均衡策略名；插件策略仍可使用任意自定义名称。 */
public enum BuiltinLoadBalanceStrategy {

    ROUND_ROBIN("round_robin"),
    RANDOM("random"),
    WEIGHTED_ROUND_ROBIN("weighted_round_robin"),
    IP_HASH("ip_hash"),
    LEAST_CONNECTIONS("least_connections");

    private final String configName;

    BuiltinLoadBalanceStrategy(String configName) {
        this.configName = configName;
    }

    public String configName() {
        return configName;
    }

    public static List<String> configNames() {
        return Arrays.stream(values()).map(BuiltinLoadBalanceStrategy::configName).toList();
    }
}
