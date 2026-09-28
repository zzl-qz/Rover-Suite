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
                AgentCapability.GATEWAY_METRICS_QUERY, AgentCapability.TRACE_QUERY, AgentCapability.CONFIG_READ,
                AgentCapability.EVENT_QUERY, AgentCapability.LOG_QUERY, AgentCapability.KNOWLEDGE_RETRIEVAL),
                registry.selectable().stream().map(CapabilityDescriptor::id).toList());
        assertTrue(registry.selectable().stream().allMatch(item -> item.risk() == CapabilityRisk.READ_ONLY));
        assertEquals(8, registry.all().size());
    }

    @Test
    void registeredButUnconnectedCapabilitiesAreNotSelectable() {
        // 已登记、但尚未接入数据适配器：仍然登记着，但不可被计划选择、也不在执行映射里。
        CapabilityRegistry partial = registryWithout(AgentCapability.CONFIG_READ);
        assertTrue(partial.descriptor(AgentCapability.CONFIG_READ).isPresent());
        assertFalse(partial.selectable(AgentCapability.CONFIG_READ));
        assertFalse(partial.descriptor(AgentCapability.CONFIG_READ).orElseThrow().available());
        assertTrue(partial.selectable(AgentCapability.EVENT_QUERY), "未收口的能力仍可被选择");
    }

    /** 把指定能力标为「已登记但未接入」，用于验证清单与选择范围的收口。 */
    private static CapabilityRegistry registryWithout(AgentCapability unavailable) {
        List<CapabilityDescriptor> descriptors = CapabilityRegistry.standard().all().stream()
                .map(item -> item.id() == unavailable
                        ? new CapabilityDescriptor(item.id(), item.name(), item.description(), item.risk(),
                                item.supportedTargetTypes(), false, item.stepType(), item.stepName())
                        : item)
                .toList();
        return new CapabilityRegistry(descriptors);
    }

    @Test
    void selectableCapabilitiesReturnedAsSetMatchesDescriptors() {
        assertEquals(registry.selectable().stream().map(CapabilityDescriptor::id).toList(),
                List.copyOf(registry.selectableCapabilities()));
    }
}