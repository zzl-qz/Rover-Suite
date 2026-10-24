package com.rover.agent.runtime.knowledge;

import com.rover.agent.core.model.KnowledgeDocumentVersion;
import com.rover.agent.core.model.ResourceScope;
import com.rover.agent.core.port.KnowledgeEntry;
import com.rover.agent.core.port.KnowledgeReadPort;
import com.rover.agent.core.repository.KnowledgeDocumentRepository;
import java.util.ArrayList;
import java.util.List;

/** Read-only retrieval of approved, unexpired built-in and scope-authorized documents. */
public final class ScopedKnowledgeStore implements KnowledgeReadPort {
    private final KnowledgeDocumentRepository documents;
    private final ResourceScope scope;

    public ScopedKnowledgeStore(KnowledgeDocumentRepository documents, ResourceScope scope) {
        this.documents = documents;
        this.scope = scope;
    }

    @Override public List<KnowledgeEntry> search(String query, int limit) {
        int cap = limit <= 0 ? 5 : Math.min(limit, 5);
        if (query == null || query.isBlank()) return List.of();
        var entries = new ArrayList<KnowledgeEntry>();
        long now = System.currentTimeMillis();
        for (KnowledgeDocumentVersion version : documents.approved(scope, now, 1000)) {
            entries.add(new KnowledgeEntry(version.documentId(), version.title(), version.content(), version.keywords(),
                    version.version(), version.sourceVersion()));
        }
        entries.addAll(com.rover.agent.runtime.knowledge.InMemoryKnowledgeStore.seedFaq());
        return new InMemoryKnowledgeStore(entries).search(query, cap);
    }
}
