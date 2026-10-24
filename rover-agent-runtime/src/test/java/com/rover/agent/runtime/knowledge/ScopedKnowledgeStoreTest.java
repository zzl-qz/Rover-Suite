package com.rover.agent.runtime.knowledge;

import static org.junit.jupiter.api.Assertions.*;
import com.rover.agent.core.model.KnowledgeDocumentVersion;
import com.rover.agent.core.model.ResourceScope;
import com.rover.agent.runtime.repository.JdbcAgentStore;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScopedKnowledgeStoreTest {
    @TempDir Path temp;
    @Test void onlyApprovedUnexpiredDocumentsInExactScopeAreSearchableAcrossRestartAndRevoke() throws Exception {
        var scope = new ResourceScope("org", "ops", "dev");
        var otherScope = new ResourceScope("org", "ops", "prod");
        var memory = new com.rover.agent.runtime.repository.InMemoryKnowledgeDocumentRepository();
        var memoryDraft = memory.createDraft("same-id", scope, "draft", "draft text", List.of(), "git:0", "writer",
                System.currentTimeMillis()+60_000);
        assertThrows(IllegalStateException.class, () -> memory.createDraft("same-id", scope, "second", "active first",
                List.of(), "git:1", "writer", System.currentTimeMillis()+60_000));
        assertThrows(IllegalStateException.class, () -> memory.createDraft("same-id", otherScope, "cross-scope",
                "no", List.of(), "git:2", "writer", System.currentTimeMillis()+60_000));
        assertThrows(IllegalArgumentException.class, () -> memory.createDraft("expired", scope, "expired", "no",
                List.of(), "git:3", "writer", System.currentTimeMillis()-1));
        var approvedMemory = memory.transition(memoryDraft.documentId(), memoryDraft.version(), KnowledgeDocumentVersion.Status.DRAFT,
                value -> value.approve("reviewer", System.currentTimeMillis())).orElseThrow();
        assertTrue(memory.approved(scope, System.currentTimeMillis(), 10).stream().anyMatch(v -> v.version() == 1));
        var memoryV2 = memory.createDraft("same-id", scope, "replacement", "new content", List.of(), "git:5",
                "writer", System.currentTimeMillis()+60_000);
        assertEquals(2, memoryV2.version());
        memory.transition(memoryV2.documentId(), memoryV2.version(), KnowledgeDocumentVersion.Status.DRAFT,
                value -> value.approve("reviewer", System.currentTimeMillis())).orElseThrow();
        assertEquals(List.of(2), memory.approved(scope, System.currentTimeMillis(), 10).stream()
                .filter(v -> v.documentId().equals("same-id")).map(KnowledgeDocumentVersion::version).toList());
        assertThrows(IllegalStateException.class, () -> memoryDraft.approve("late-reviewer", memoryDraft.expiresAtMillis()));
        var tampered = new KnowledgeDocumentVersion(approvedMemory.documentId(), approvedMemory.version(), approvedMemory.scope(),
                approvedMemory.title(), approvedMemory.content(), List.of("tampered"), approvedMemory.sourceVersion(),
                approvedMemory.createdBy(), approvedMemory.createdAtMillis(), approvedMemory.expiresAtMillis(),
                approvedMemory.status(), approvedMemory.reviewedBy(), approvedMemory.reviewedAtMillis(), null, 0);
        assertThrows(IllegalArgumentException.class, () -> memory.transition(approvedMemory.documentId(), approvedMemory.version(),
                KnowledgeDocumentVersion.Status.APPROVED, ignored -> tampered));
        String path = temp.resolve("knowledge").toString();
        long now = System.currentTimeMillis();
        try (JdbcAgentStore store = new JdbcAgentStore(path)) {
            var drafts = store.knowledgeDocuments();
            var draft = drafts.createDraft("rb-routing", scope, "订单路由排查", "检查 Gateway 路由和上游实例健康",
                    List.of("订单", "路由", "上游"), "git:abc123", "writer", now + 60_000);
            drafts.createDraft("rb-prod", otherScope, "生产路由排查", "prod only secret doc", List.of("订单"), "git:def", "writer", now+60_000);
            var reader = new ScopedKnowledgeStore(drafts, scope);
            assertTrue(reader.search("订单路由", 5).stream().noneMatch(hit -> hit.id().equals("rb-routing")));
            var approved = drafts.transition(draft.documentId(), draft.version(), KnowledgeDocumentVersion.Status.DRAFT,
                    value -> value.approve("reviewer", System.currentTimeMillis())).orElseThrow();
            assertTrue(reader.search("订单路由", 5).stream().anyMatch(hit -> hit.id().equals("rb-routing")
                    && hit.version() == 1 && hit.sourceVersion().equals("git:abc123")));
            assertTrue(reader.search("生产路由", 5).stream().noneMatch(hit -> hit.id().equals("rb-prod")));
            var v2 = drafts.createDraft("rb-routing", scope, "订单路由排查 v2", "新的处理办法",
                    List.of("订单"), "git:next", "writer", System.currentTimeMillis()+1_000);
            assertTrue(reader.search("订单路由", 5).stream().anyMatch(hit -> hit.id().equals("rb-routing") && hit.version() == 1),
                    "a draft must not hide the currently published version");
            Thread.sleep(1_100);
            assertEquals(List.of(1), drafts.approved(scope, v2.expiresAtMillis()+1, 10).stream()
                    .filter(value -> value.documentId().equals("rb-routing"))
                    .map(KnowledgeDocumentVersion::version).toList(),
                    "an expired, never-published draft must not hide the current approved version");
            var v3 = drafts.createDraft("rb-routing", scope, "订单路由排查 v3", "新的处理办法 v3",
                    List.of("订单"), "git:next-v3", "writer", System.currentTimeMillis()+60_000);
            var approvedV2 = drafts.transition(v3.documentId(), v3.version(), KnowledgeDocumentVersion.Status.DRAFT,
                    value -> value.approve("reviewer", System.currentTimeMillis())).orElseThrow();
            assertTrue(reader.search("订单路由", 5).stream().anyMatch(hit -> hit.id().equals("rb-routing") && hit.version() == 3));
            assertTrue(reader.search("订单路由", 5).stream().noneMatch(hit -> hit.id().equals("rb-routing") && hit.version() == 1),
                    "approval of a replacement supersedes the previous version");
            assertEquals(List.of(3), drafts.approved(scope, v3.createdAtMillis(), 10).stream()
                    .map(KnowledgeDocumentVersion::version).toList());
            assertTrue(drafts.approved(scope, v3.expiresAtMillis(), 10).isEmpty());
            assertEquals(approved, drafts.find("rb-routing", 1).orElseThrow());
        }
        try (JdbcAgentStore reopened = new JdbcAgentStore(path)) {
            assertTrue(new ScopedKnowledgeStore(reopened.knowledgeDocuments(), scope).search("订单路由", 5)
                    .stream().anyMatch(hit -> hit.id().equals("rb-routing")));
            var old = reopened.knowledgeDocuments().find("rb-routing", 1).orElseThrow();
            reopened.knowledgeDocuments().transition("rb-routing", 3, KnowledgeDocumentVersion.Status.APPROVED,
                    value -> value.revoke("security-admin", System.currentTimeMillis())).orElseThrow();
            assertTrue(new ScopedKnowledgeStore(reopened.knowledgeDocuments(), scope).search("订单路由", 5)
                    .stream().noneMatch(hit -> hit.id().equals("rb-routing")));
            assertEquals(old.content(), reopened.knowledgeDocuments().find("rb-routing", 1).orElseThrow().content());
            var v3 = reopened.knowledgeDocuments().createDraft("rb-routing", scope, "新版", "更新的路由处理办法",
                    List.of("订单"), "git:next", "writer", System.currentTimeMillis()+60_000);
            assertEquals(4, v3.version());
        }
    }
}
