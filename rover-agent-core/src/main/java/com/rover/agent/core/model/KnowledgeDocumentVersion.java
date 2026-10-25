package com.rover.agent.core.model;

import java.util.List;

/** Immutable content version; approval and revocation state are retained with the version. */
public record KnowledgeDocumentVersion(String documentId, int version, ResourceScope scope,
        String title, String content, List<String> keywords, String sourceVersion,
        String createdBy, long createdAtMillis, long expiresAtMillis, Status status, String sourceIncidentId,
        String reviewedBy, long reviewedAtMillis, String revokedBy, long revokedAtMillis) {
    public enum Status { DRAFT, APPROVED, REJECTED, REVOKED }

    public KnowledgeDocumentVersion {
        if (documentId == null || documentId.isBlank() || documentId.length() > 64 || version < 1
                || scope == null || title == null || title.isBlank() || title.length() > 200
                || content == null || content.isBlank() || content.length() > 40_000
                || sourceVersion == null || sourceVersion.isBlank() || sourceVersion.length() > 200
                || createdBy == null || createdBy.isBlank() || createdBy.length() > 128
                || createdAtMillis < 1 || expiresAtMillis <= createdAtMillis || status == null) {
            throw new IllegalArgumentException("知识文档版本字段无效");
        }
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
        if (keywords.size() > 50 || keywords.stream().anyMatch(k -> k == null || k.isBlank() || k.length() > 100)) {
            throw new IllegalArgumentException("知识关键词无效");
        }
        if (sourceIncidentId != null && (sourceIncidentId.isBlank() || sourceIncidentId.length() > 64)) {
            throw new IllegalArgumentException("复盘来源事件标识无效");
        }
        if (status == Status.DRAFT && (reviewedBy != null || reviewedAtMillis != 0 || revokedBy != null || revokedAtMillis != 0)
                || (status == Status.APPROVED || status == Status.REJECTED)
                && (reviewedBy == null || reviewedBy.isBlank() || reviewedAtMillis < createdAtMillis
                || revokedBy != null || revokedAtMillis != 0)
                || status == Status.REVOKED && (reviewedBy == null || reviewedBy.isBlank() || reviewedAtMillis < createdAtMillis
                || revokedBy == null || revokedBy.isBlank() || revokedAtMillis < reviewedAtMillis)) {
            throw new IllegalArgumentException("知识文档审核状态无效");
        }
    }

    public KnowledgeDocumentVersion(String documentId, int version, ResourceScope scope, String title, String content,
            List<String> keywords, String sourceVersion, String createdBy, long createdAtMillis, long expiresAtMillis,
            Status status, String reviewedBy, long reviewedAtMillis, String revokedBy, long revokedAtMillis) {
        this(documentId, version, scope, title, content, keywords, sourceVersion, createdBy, createdAtMillis,
                expiresAtMillis, status, null, reviewedBy, reviewedAtMillis, revokedBy, revokedAtMillis);
    }

    public KnowledgeDocumentVersion approve(String reviewer, long at) {
        if (at < createdAtMillis || at >= expiresAtMillis) {
            throw new IllegalStateException("知识文档已过期，不能批准");
        }
        return new KnowledgeDocumentVersion(documentId, version, scope, title, content, keywords, sourceVersion,
                createdBy, createdAtMillis, expiresAtMillis, Status.APPROVED, sourceIncidentId, reviewer, at, null, 0);
    }

    public KnowledgeDocumentVersion reject(String reviewer, long at) {
        if (at < createdAtMillis || at >= expiresAtMillis) {
            throw new IllegalStateException("知识文档已过期，不能驳回");
        }
        return new KnowledgeDocumentVersion(documentId, version, scope, title, content, keywords, sourceVersion,
                createdBy, createdAtMillis, expiresAtMillis, Status.REJECTED, sourceIncidentId, reviewer, at, null, 0);
    }

    public KnowledgeDocumentVersion revoke(String actor, long at) {
        return new KnowledgeDocumentVersion(documentId, version, scope, title, content, keywords, sourceVersion,
                createdBy, createdAtMillis, expiresAtMillis, Status.REVOKED, sourceIncidentId, reviewedBy, reviewedAtMillis, actor, at);
    }
}
