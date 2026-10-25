package com.rover.agent.runtime.repository;

import com.rover.agent.core.model.KnowledgeDocumentVersion;
import com.rover.agent.core.model.ResourceScope;
import com.rover.agent.core.repository.KnowledgeDocumentRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.UnaryOperator;

/** Explicit development-mode repository; production uses JdbcAgentStore. */
public final class InMemoryKnowledgeDocumentRepository implements KnowledgeDocumentRepository {
    private final ConcurrentMap<String, List<KnowledgeDocumentVersion>> versions = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, com.rover.agent.core.model.IncidentRetrospective> incidentDrafts = new ConcurrentHashMap<>();

    @Override public synchronized KnowledgeDocumentVersion createIncidentDraft(
            com.rover.agent.core.model.IncidentRetrospective retrospective) {
        var existing = incidentDrafts.get(retrospective.documentId());
        if (existing != null) {
            if (!existing.equals(retrospective)) {
                throw new IllegalStateException("案例复盘草稿已存在");
            }
            return find(retrospective.documentId(), 1).orElseThrow();
        }
        var draft = createDraft(retrospective.documentId(), retrospective.scope(), retrospective.title(),
                retrospective.summary() + "\n\n根因：" + retrospective.rootCause() + "\n\n处置："
                        + retrospective.resolution() + "\n\n效果：" + retrospective.outcome(),
                retrospective.failureModes(), "incident:" + retrospective.incidentId(), retrospective.incidentId(),
                retrospective.createdBy(), retrospective.expiresAtMillis());
        incidentDrafts.put(retrospective.documentId(), retrospective);
        return draft;
    }
    @Override public Optional<com.rover.agent.core.model.IncidentRetrospective> incidentDraft(String documentId) {
        return Optional.ofNullable(incidentDrafts.get(documentId));
    }
    @Override public synchronized KnowledgeDocumentVersion createDraft(String id, ResourceScope scope, String title,
            String content, List<String> keywords, String sourceVersion, String actor, long expiresAtMillis) {
        return createDraft(id, scope, title, content, keywords, sourceVersion, null, actor, expiresAtMillis);
    }
    @Override public synchronized KnowledgeDocumentVersion createDraft(String id, ResourceScope scope, String title,
            String content, List<String> keywords, String sourceVersion, String sourceIncidentId, String actor, long expiresAtMillis) {
        List<KnowledgeDocumentVersion> history = versions.getOrDefault(id, List.of());
        if (!history.isEmpty()) {
            var current = history.get(history.size() - 1);
            if (!current.scope().equals(scope)) { throw new IllegalStateException("文档资源范围不可变"); }
            if (current.expiresAtMillis() > System.currentTimeMillis()
                    && current.status() == KnowledgeDocumentVersion.Status.DRAFT) {
                throw new IllegalStateException("文档当前版本尚未撤销或过期");
            }
        }
        if (expiresAtMillis <= System.currentTimeMillis()) { throw new IllegalArgumentException("知识版本必须晚于当前时间过期"); }
        var document = new KnowledgeDocumentVersion(id, history.size() + 1, scope, title, content, keywords,
                sourceVersion, actor, System.currentTimeMillis(), expiresAtMillis, KnowledgeDocumentVersion.Status.DRAFT,
                sourceIncidentId, null, 0, null, 0);
        var next = new ArrayList<>(history); next.add(document); versions.put(id, List.copyOf(next)); return document;
    }
    @Override public Optional<KnowledgeDocumentVersion> find(String id, int version) {
        return versions.getOrDefault(id, List.of()).stream().filter(v -> v.version() == version).findFirst();
    }
    @Override public List<KnowledgeDocumentVersion> list(ResourceScope scope) {
        return versions.values().stream().flatMap(List::stream).filter(v -> v.scope().equals(scope))
                .sorted(Comparator.comparing(KnowledgeDocumentVersion::documentId).thenComparingInt(KnowledgeDocumentVersion::version)).toList();
    }
    @Override public List<KnowledgeDocumentVersion> approved(ResourceScope scope, long at, int limit) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("limit must be 1..1000");
        var scoped = list(scope);
        return scoped.stream().filter(v -> v.status() == KnowledgeDocumentVersion.Status.APPROVED && v.expiresAtMillis() > at)
                .filter(v -> scoped.stream().noneMatch(newer -> newer.documentId().equals(v.documentId())
                        && newer.version() > v.version() && (newer.status() == KnowledgeDocumentVersion.Status.APPROVED
                        || newer.status() == KnowledgeDocumentVersion.Status.REVOKED) && newer.createdAtMillis() <= at))
                .sorted(Comparator.comparing(KnowledgeDocumentVersion::documentId).thenComparingInt(KnowledgeDocumentVersion::version).reversed())
                .limit(limit).toList();
    }
    @Override public synchronized Optional<KnowledgeDocumentVersion> transition(String id, int version,
            KnowledgeDocumentVersion.Status expected, UnaryOperator<KnowledgeDocumentVersion> mutation) {
        var history = versions.get(id); if (history == null) return Optional.empty();
        for (int i=0;i<history.size();i++) {
            var current=history.get(i); if (current.version()!=version) continue;
            if (current.status()!=expected) return Optional.empty();
            if (expected == KnowledgeDocumentVersion.Status.DRAFT && current.expiresAtMillis() <= System.currentTimeMillis()) {
                return Optional.empty();
            }
            var updated=mutation.apply(current);
            if (!updated.documentId().equals(id) || updated.version()!=version || !updated.scope().equals(current.scope())
                    || !updated.title().equals(current.title()) || !updated.content().equals(current.content())
                    || !updated.keywords().equals(current.keywords()) || !updated.sourceVersion().equals(current.sourceVersion())
                    || !java.util.Objects.equals(updated.sourceIncidentId(), current.sourceIncidentId())
                    || !updated.createdBy().equals(current.createdBy()) || updated.createdAtMillis()!=current.createdAtMillis()
                    || updated.expiresAtMillis()!=current.expiresAtMillis()) throw new IllegalArgumentException("knowledge version is immutable");
            if (updated.status() == KnowledgeDocumentVersion.Status.APPROVED && updated.sourceIncidentId() != null
                    && Optional.ofNullable(incidentDrafts.get(id)).filter(review -> review.incidentId().equals(updated.sourceIncidentId())).isEmpty()) {
                throw new IllegalStateException("案例复盘来源记录缺失，不能批准");
            }
            var next=new ArrayList<>(history); next.set(i,updated); versions.put(id,List.copyOf(next)); return Optional.of(updated);
        }
        return Optional.empty();
    }
}
