package com.rover.common.spi.loadbalance;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashSet;
import org.junit.jupiter.api.Test;

class BuiltinLoadBalanceStrategyTest {

    @Test
    void builtInNamesAreUniqueAndDefaultAliasStaysCompatible() {
        assertEquals(
                BuiltinLoadBalanceStrategy.values().length,
                new HashSet<>(BuiltinLoadBalanceStrategy.configNames()).size());
        assertEquals(
                BuiltinLoadBalanceStrategy.ROUND_ROBIN.configName(),
                LoadBalancer.ROUND_ROBIN);
    }
}
