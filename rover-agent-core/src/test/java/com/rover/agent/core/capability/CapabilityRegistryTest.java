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
        // 已登记、但尚未接入数据适配器：出现在清单里让用户看到边界，但不可被计划选择。
        CapabilityRegistry partial = registryWithout(AgentCapability.CONFIG_READ);
        assertTrue(partial.descriptor(AgentCapability.CONFIG_READ).isPresent());
        assertFalse(partial.selectable(AgentCapability.CONFIG_READ));
        assertFalse(partial.descriptor(AgentCapability.CONFIG_READ).orElseThrow().available());
        assertTrue(partial.selectable(AgentCapability.EVENT_QUERY), "未收口的能力仍可被选择");
    }

    @Test
    void capabilityListIsGeneratedFromRegistryNotFromModel() {
        String described = registry.describe();

        for (AgentCapability capability : List.of(AgentCapability.ROUTE_QUERY, AgentCapability.INSTANCE_QUERY,
                AgentCapability.GATEWAY_METRICS_QUERY, AgentCapability.TRACE_QUERY, AgentCapability.CONFIG_READ,
                AgentCapability.EVENT_QUERY, AgentCapability.LOG_QUERY, AgentCapability.KNOWLEDGE_RETRIEVAL)) {
            assertTrue(described.contains(capability.name()), "能力清单缺少 " + capability);
        }
        assertFalse(described.contains("尚未开放的能力"), "八个能力全部接入时不应出现未开放段落");
        // 处置边界与能力清单一起给出：不会让用户以为可以「让它去执行」。
        assertTrue(described.contains("不执行任何写操作"));
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
    void introductionExplainsIdentityBeforeTheSameCapabilityList() {
        String introduction = registry.introduce();

        // 自我介绍与能力清单同源：先讲清身份与可以直接问什么，再附完整清单。
        assertTrue(introduction.contains("我是 Rover Ops Agent"));
        assertTrue(introduction.contains("网关 QPS 多少"));
        assertTrue(introduction.contains(registry.describe()), "自我介绍必须内嵌同一份能力清单");
    }

    @Test
    void briefIntroductionStaysShortAndStillOffersExamples() {
        String brief = registry.introduceBriefly();

        assertTrue(brief.contains("还没能对上具体的查询或排查目标"), "先说没听懂");
        assertTrue(brief.contains("我就能直接查"), "要说清怎样问才查得到，而不是只回一句听不懂");
        assertTrue(brief.contains("您可以这样问我"));
        // 能做什么仍取自同一份注册表，不另写一份说法
        registry.selectable().forEach(item -> assertTrue(brief.contains(item.name())));
        // 简短版不铺开完整清单：枚举 ID 与风险级别只出现在完整版里
        assertFalse(brief.contains("风险级别"));
        assertTrue(brief.length() < registry.introduce().length());
    }

    @Test
    void selectableCapabilitiesReturnedAsSetMatchesDescriptors() {
        assertEquals(registry.selectable().stream().map(CapabilityDescriptor::id).toList(),
                List.copyOf(registry.selectableCapabilities()));
    }
}