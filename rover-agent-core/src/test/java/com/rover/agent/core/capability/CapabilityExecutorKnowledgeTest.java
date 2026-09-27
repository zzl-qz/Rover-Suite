package com.rover.agent.core.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rover.agent.core.model.EvidenceType;
import com.rover.agent.core.model.ResourceTarget;
import com.rover.agent.core.port.KnowledgeEntry;
import com.rover.agent.core.port.KnowledgeReadPort;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 知识检索能力：走 KnowledgeReadPort 检索，产出 KNOWLEDGE 证据；空问题返回失败边界。 */
class CapabilityExecutorKnowledgeTest {

    private CapabilityExecutor executor(KnowledgeReadPort knowledge) {
        return new CapabilityExecutor(null, null, null, null, null, null, null, knowledge,
                CapabilityRegistry.standard());
    }

    @Test
    void readKnowledgeProducesKnowledgeEvidence() {
        KnowledgeReadPort knowledge = (query, limit) -> List.of(
                new KnowledgeEntry("kb-1", "怎么配置限流", "设置阈值后热更新", List.of("限流")));
        CapabilityExecutor executor = executor(knowledge);

        CapabilityResult result = executor.execute(AgentCapability.KNOWLEDGE_RETRIEVAL,
                ResourceTarget.unknown(), "限流怎么配置", "task-1");

        assertTrue(result.executed());
        assertEquals(1, result.evidence().size());
        assertEquals(EvidenceType.KNOWLEDGE, result.evidence().get(0).type());
        assertEquals("运维知识库", result.evidence().get(0).source());
        assertTrue(result.evidence().get(0).summary().contains("怎么配置限流"));
    }

    @Test
    void readKnowledgeRequiresAQuery() {
        CapabilityExecutor executor = executor((query, limit) -> List.of());

        CapabilityResult result = executor.execute(AgentCapability.KNOWLEDGE_RETRIEVAL,
                ResourceTarget.unknown(), "", "task-1");

        assertTrue(result.executed());
        assertTrue(result.evidence().isEmpty());
        assertTrue(result.limitations().get(0).contains("缺少要检索的问题"));
    }
}
