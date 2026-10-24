package com.rover.agent.core.repository;

import com.rover.agent.core.model.KnowledgeDocumentVersion;
import com.rover.agent.core.model.ResourceScope;
import java.util.List;
import java.util.Optional;

/** Durable, versioned runbook and operational knowledge metadata. */
public interface KnowledgeDocumentRepository {
    KnowledgeDocumentVersion createDraft(String documentId, ResourceScope scope, String title, String content,
            List<String> keywords, String sourceVersion, String actor, long expiresAtMillis);
    Optional<KnowledgeDocumentVersion> find(String documentId, int version);
    List<KnowledgeDocumentVersion> list(ResourceScope scope);
    List<KnowledgeDocumentVersion> approved(ResourceScope scope, long asOfMillis, int limit);
    Optional<KnowledgeDocumentVersion> transition(String documentId, int version,
            KnowledgeDocumentVersion.Status expected, java.util.function.UnaryOperator<KnowledgeDocumentVersion> mutation);
}
