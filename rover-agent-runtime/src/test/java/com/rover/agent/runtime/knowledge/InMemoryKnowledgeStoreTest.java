package com.rover.agent.runtime.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rover.agent.core.port.KnowledgeEntry;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 内存知识库：关键词检索按相关度排序，未知问题返回空而非硬凑。 */
class InMemoryKnowledgeStoreTest {

    private final InMemoryKnowledgeStore store = new InMemoryKnowledgeStore(InMemoryKnowledgeStore.seedFaq());

    @Test
    void searchReturnsMostRelevantEntryFirst() {
        List<KnowledgeEntry> hits = store.search("怎么配置限流阈值", 3);

        assertFalse(hits.isEmpty());
        assertEquals("kb-rate-limit", hits.get(0).id(), "最相关的限流条目应排第一");
    }

    @Test
    void searchReturnsEmptyForUnrelatedQuery() {
        assertTrue(store.search("今天天气怎么样", 5).isEmpty());
    }

    @Test
    void seedFaqCoversCommonOperationalQuestions() {
        List<KnowledgeEntry> seed = InMemoryKnowledgeStore.seedFaq();
        assertFalse(seed.isEmpty());
        assertTrue(seed.stream().anyMatch(e -> e.keywords().contains("限流")));
        assertTrue(seed.stream().anyMatch(e -> e.keywords().contains("灰度")));
    }
}
