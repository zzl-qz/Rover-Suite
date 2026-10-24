package com.rover.admin.agent;

import com.rover.admin.security.AgentSecurityService;
import com.rover.agent.core.model.KnowledgeDocumentVersion;
import com.rover.agent.core.model.ResourceScope;
import com.rover.agent.core.port.AgentAccess.Permission;
import com.rover.agent.runtime.repository.AgentStore;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Reviewable, immutable versioned knowledge lifecycle; published documents remain read-only evidence. */
@RestController
@RequestMapping("/api/agent/knowledge/documents")
public final class KnowledgeDocumentController {
    private static final long MAX_TTL = Duration.ofDays(365).toMillis();
    private final AgentStore store;
    private final com.rover.agent.core.port.RouteControlPort control;
    private final AgentSecurityService security;

    public KnowledgeDocumentController(AgentStore store, com.rover.agent.core.port.RouteControlPort control, AgentSecurityService security) {
        this.store=store; this.control=control; this.security=security;
    }
    @GetMapping public Object list(Authentication actor) {
        security.require(actor.getName(), Permission.POLICY_ADMIN);
        return store.knowledgeDocuments().list(scope());
    }
    @PostMapping public ResponseEntity<?> create(@RequestBody Map<String,Object> body, Authentication actor) {
        return createVersion(UUID.randomUUID().toString(), body, actor);
    }
    @PostMapping("/{documentId}/versions") public ResponseEntity<?> createNextVersion(@PathVariable String documentId,
            @RequestBody Map<String,Object> body, Authentication actor) {
        security.require(actor.getName(), Permission.POLICY_ADMIN);
        if (!store.persistent() || !store.durable().healthy()) return ResponseEntity.status(503).body(Map.of("error", "持久知识库未就绪"));
        boolean visible = store.knowledgeDocuments().list(scope()).stream().anyMatch(v -> v.documentId().equals(documentId));
        if (!visible) return ResponseEntity.notFound().build();
        return createVersion(documentId, body, actor);
    }
    private ResponseEntity<?> createVersion(String documentId, Map<String,Object> body, Authentication actor) {
        security.require(actor.getName(), Permission.POLICY_ADMIN);
        if (!store.persistent() || !store.durable().healthy()) return ResponseEntity.status(503).body(Map.of("error", "持久知识库未就绪"));
        long ttl=number(body.get("ttlMillis"), Duration.ofDays(90).toMillis());
        if (ttl < 60_000 || ttl > MAX_TTL) return ResponseEntity.badRequest().body(Map.of("error", "有效期必须为 1 分钟至 365 天"));
        try {
            var draft=store.knowledgeDocuments().createDraft(documentId, scope(), text(body.get("title"),200),
                    text(body.get("content"),40_000), strings(body.get("keywords")), text(body.get("sourceVersion"),200),
                    actor.getName(), System.currentTimeMillis()+ttl);
            return ResponseEntity.status(HttpStatus.CREATED).body(draft);
        } catch (IllegalArgumentException | IllegalStateException invalid) {
            HttpStatus status = invalid instanceof IllegalStateException ? HttpStatus.CONFLICT : HttpStatus.BAD_REQUEST;
            return ResponseEntity.status(status).body(Map.of("error", invalid.getMessage()));
        }
    }
    @PostMapping("/{documentId}/versions/{version}/approve") public Object approve(@PathVariable String documentId,
            @PathVariable int version, Authentication actor) {
        security.require(actor.getName(), Permission.POLICY_ADMIN);
        var current=store.knowledgeDocuments().find(documentId,version).filter(v -> v.scope().equals(scope())).orElse(null);
        if (current==null) return ResponseEntity.notFound().build();
        if (current.createdBy().equals(actor.getName())) return ResponseEntity.status(403).body(Map.of("error", "创建人不能审核自己的知识文档"));
        if (current.expiresAtMillis()<=System.currentTimeMillis()) return ResponseEntity.status(409).body(Map.of("error", "文档已过期"));
        return store.knowledgeDocuments().transition(documentId,version,KnowledgeDocumentVersion.Status.DRAFT,
                value -> value.approve(actor.getName(),System.currentTimeMillis()))
                .<Object>map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.status(409).body(Map.of("error", "文档状态已变化")));
    }
    @PostMapping("/{documentId}/versions/{version}/revoke") public Object revoke(@PathVariable String documentId,
            @PathVariable int version, Authentication actor) {
        security.require(actor.getName(), Permission.POLICY_ADMIN);
        var current=store.knowledgeDocuments().find(documentId,version).filter(v -> v.scope().equals(scope())).orElse(null);
        if (current==null) return ResponseEntity.notFound().build();
        return store.knowledgeDocuments().transition(documentId,version,KnowledgeDocumentVersion.Status.APPROVED,
                value -> value.revoke(actor.getName(),System.currentTimeMillis()))
                .<Object>map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.status(409).body(Map.of("error", "仅已批准版本可撤销")));
    }
    private ResourceScope scope() { return control.resourceScope(); }
    private static String text(Object value,int max) { if (!(value instanceof String s) || s.isBlank() || s.length()>max) throw new IllegalArgumentException("文档字段无效"); return s.strip(); }
    private static long number(Object value,long fallback) { return value instanceof Number n ? n.longValue() : fallback; }
    private static List<String> strings(Object value) {
        if (value==null) return List.of(); if (!(value instanceof List<?> list) || list.size()>50) throw new IllegalArgumentException("关键词数量无效");
        return list.stream().map(item -> text(item,100)).toList();
    }
}
