package com.rover.agent.core.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 能力注册表：可被 Planner 选择的范围必须与「真的接入了只读数据源」一致。 */
class CapabilityRegistryTest {

    private final CapabilityRegistry registry = CapabilityRegistry.standard();

    @Test
    void onlyConnectedReadOnlyCapabilitiesAreSelectable() {
        assertEquals(List.of(AgentCapability.ROUTE_QUERY, AgentCapability.INSTANCE_QUERY,
                AgentCapability.GATEWAY_METRICS_QUERY, AgentCapability.TRACE_QUERY), registry.selectable().stream()
                .map(CapabilityDescriptor::id).toList());
        assertTrue(registry.selectable().stream().allMatch(item -> item.risk() == CapabilityRisk.READ_ONLY));
        assertEquals(6, registry.all().size());
    }

    @Test
    void registeredButUnconnectedCapabilitiesAreNotSelectable() {
        // 已登记、但尚未接入数据适配器：出现在清单里让用户看到边界，但不可被计划选择。
        assertTrue(registry.descriptor(AgentCapability.CONFIG_READ).isPresent());
        assertFalse(registry.selectable(AgentCapability.CONFIG_READ));
        assertFalse(registry.selectable(AgentCapability.EVENT_QUERY));
        assertFalse(registry.descriptor(AgentCapability.CONFIG_READ).orElseThrow().available());
    }

    @Test
    void capabilityListIsGeneratedFromRegistryNotFromModel() {
        String described = registry.describe();

        for (AgentCapability capability : List.of(AgentCapability.ROUTE_QUERY, AgentCapability.INSTANCE_QUERY,
                AgentCapability.GATEWAY_METRICS_QUERY, AgentCapability.TRACE_QUERY)) {
            assertTrue(described.contains(capability.name()), "能力清单缺少 " + capability);
        }
        assertTrue(described.contains("尚未开放的能力"));
        assertTrue(described.contains(AgentCapability.CONFIG_READ.name()));
        // 处置边界与能力清单一起给出：不会让用户以为可以「让它去执行」。
        assertTrue(described.contains("不执行任何写操作"));
    }

    @Test
    void introductionExplainsIdentityBeforeTheSameCapabilityList() {
        String introduction = registry.introduce();

        // 自我介绍与能力清单同源：先讲清身份与可以直接问什么，再附完整清单。
        assertTrue(introduction.contains("我是 Rover Ops Agent"));
        assertTrue(introduction.contains("网关 QPS 多少"));
        assertTrue(introduction.contains(registry.describe()), "自我介绍必须内嵌同一份能力清单");
    }

    @Test
    void selectableCapabilitiesReturnedAsSetMatchesDescriptors() {
        assertEquals(registry.selectable().stream().map(CapabilityDescriptor::id).toList(),
                List.copyOf(registry.selectableCapabilities()));
    }
}