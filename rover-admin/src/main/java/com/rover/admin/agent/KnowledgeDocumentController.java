package com.rover.admin.agent;

import com.rover.admin.security.AgentSecurityService;
import com.rover.agent.core.model.KnowledgeDocumentVersion;
import com.rover.agent.core.model.Incident;
import com.rover.agent.core.model.Session;
import com.rover.agent.core.model.ResourceScope;
import com.rover.agent.core.port.AgentAccess.Permission;
import com.rover.agent.runtime.repository.AgentStore;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
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

    @GetMapping("/incidents/{incidentId}/retrospectives") public ResponseEntity<?> retrospectives(
            @PathVariable String incidentId, Authentication actor) {
        security.require(actor.getName(), Permission.POLICY_ADMIN);
        if (!store.persistent() || !store.durable().healthy()) { return ResponseEntity.status(503).build(); }
        Incident incident = store.incidents().find(incidentId).orElse(null);
        Session session = incident == null ? null : store.sessions().find(incident.sessionId()).orElse(null);
        if (session == null || !Objects.equals(session.scope(), scope())
                || !Objects.equals(session.targetNamespace(), control.resourceNamespace())) {
            return ResponseEntity.notFound().build();
        }
        var versions = store.durable().caseRetrospectives(scope(), incidentId, 501);
        var records = versions.stream()
                .limit(500)
                .map(document -> new RetrospectiveRecord(document,
                        store.knowledgeDocuments().incidentDraft(document.documentId()).orElse(null)))
                .collect(Collectors.toList());
        return ResponseEntity.ok(new RetrospectiveList(records, versions.size() > 500));
    }

    @GetMapping("/{documentId}/versions/{version}/source-incident") public ResponseEntity<?> sourceIncident(
            @PathVariable String documentId, @PathVariable int version, Authentication actor) {
        security.require(actor.getName(), Permission.POLICY_ADMIN);
        var document = store.knowledgeDocuments().find(documentId, version)
                .filter(value -> value.scope().equals(scope())).orElse(null);
        if (document == null || document.sourceIncidentId() == null) { return ResponseEntity.notFound().build(); }
        Incident incident = store.incidents().find(document.sourceIncidentId()).orElse(null);
        Session session = incident == null ? null : store.sessions().find(incident.sessionId()).orElse(null);
        if (session == null || !Objects.equals(session.scope(), scope())
                || !Objects.equals(session.targetNamespace(), control.resourceNamespace())) {
            return ResponseEntity.notFound().build();
        }
        return new IncidentCaseController(store, security, control).getForReviewer(incident.incidentId(), actor);
    }
    @PostMapping public ResponseEntity<?> create(@RequestBody Map<String,Object> body, Authentication actor) {
        return createVersion(UUID.randomUUID().toString(), body, actor);
    }
    @PostMapping("/import") public ResponseEntity<?> importDrafts(@RequestBody BatchImportRequest request, Authentication actor) {
        security.require(actor.getName(), Permission.POLICY_ADMIN);
        if (!store.persistent() || !store.durable().healthy()) {
            return ResponseEntity.status(503).body(Map.of("error", "持久知识库未就绪"));
        }
        if (request == null || request.documents() == null || request.documents().isEmpty()
                || request.documents().size() > 100) {
            return ResponseEntity.badRequest().body(Map.of("error", "一次须导入 1 至 100 篇知识文档"));
        }
        long batchCharacters = request.documents().stream().filter(Objects::nonNull)
                .mapToLong(item -> length(item.title()) + length(item.content()) + length(item.sourceVersion())
                        + (item.keywords() == null ? 0 : item.keywords().stream().mapToLong(KnowledgeDocumentController::length).sum()))
                .sum();
        if (batchCharacters > 1_000_000) {
            return ResponseEntity.badRequest().body(Map.of("error", "批量文档总长度不能超过 100 万字符"));
        }
        var results = new java.util.ArrayList<BatchImportResult>();
        int index = 0;
        for (BatchImportItem item : request.documents()) {
            index++;
            if (item == null) {
                results.add(new BatchImportResult(index, null, null, "REJECTED", "文档内容无效"));
                continue;
            }
            String id = item.documentId() == null || item.documentId().isBlank()
                    ? UUID.randomUUID().toString() : item.documentId();
            var body = new java.util.HashMap<String, Object>();
            body.put("title", item.title());
            body.put("content", item.content());
            body.put("keywords", item.keywords());
            body.put("sourceVersion", item.sourceVersion());
            body.put("ttlMillis", item.ttlMillis());
            var response = createVersion(id, body, actor);
            if (response.getStatusCode().is2xxSuccessful()) {
                var draft = (KnowledgeDocumentVersion) response.getBody();
                results.add(new BatchImportResult(index, draft.documentId(), draft.version(), "DRAFT_CREATED", null));
            } else {
                String error = response.getBody() instanceof Map<?, ?> value
                        ? Objects.toString(value.get("error"), "文档未能导入") : "文档未能导入";
                results.add(new BatchImportResult(index, id, null, "REJECTED", error));
            }
        }
        long created = results.stream().filter(result -> result.status().equals("DRAFT_CREATED")).count();
        return ResponseEntity.ok(new BatchImportResponse((int) created, results.size() - (int) created, results));
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
        try {
            long ttl=number(body.get("ttlMillis"), Duration.ofDays(90).toMillis());
            if (ttl < 60_000 || ttl > MAX_TTL) return ResponseEntity.badRequest().body(Map.of("error", "有效期必须为 1 分钟至 365 天"));
            String sourceIncidentId = optionalText(body.get("sourceIncidentId"), 64);
            String sourceVersion = text(body.get("sourceVersion"), 200);
            if (sourceIncidentId != null) {
                Incident incident = store.incidents().find(sourceIncidentId).orElse(null);
                Session session = incident == null ? null : store.sessions().find(incident.sessionId()).orElse(null);
                if (incident == null || session == null || session.scope() == null || session.targetNamespace() == null
                        || !session.scope().equals(scope())
                        || !session.targetNamespace().equals(control.resourceNamespace())
                        || !sourceVersion.equals("incident:" + sourceIncidentId)) {
                    return ResponseEntity.notFound().build();
                }
            }
            var draft=store.knowledgeDocuments().createDraft(documentId, scope(), text(body.get("title"),200),
                    text(body.get("content"),40_000), strings(body.get("keywords")), sourceVersion,
                    sourceIncidentId,
                    actor.getName(), System.currentTimeMillis()+ttl);
            return ResponseEntity.status(HttpStatus.CREATED).body(draft);
        } catch (IllegalArgumentException | IllegalStateException invalid) {
            HttpStatus status = invalid instanceof IllegalStateException ? HttpStatus.CONFLICT : HttpStatus.BAD_REQUEST;
            return ResponseEntity.status(status).body(Map.of("error", invalid.getMessage()));
        }
    }
    @PostMapping("/{documentId}/versions/{version}/approve") public Object approve(@PathVariable String documentId,
            @PathVariable int version, @RequestBody(required = false) Map<String, Object> body, Authentication actor) {
        security.require(actor.getName(), Permission.POLICY_ADMIN);
        var current=store.knowledgeDocuments().find(documentId,version).filter(v -> v.scope().equals(scope())).orElse(null);
        if (current==null) return ResponseEntity.notFound().build();
        if (current.createdBy().equals(actor.getName())) return ResponseEntity.status(403).body(Map.of("error", "创建人不能审核自己的知识文档"));
        if (current.expiresAtMillis()<=System.currentTimeMillis()) return ResponseEntity.status(409).body(Map.of("error", "文档已过期"));
        String decision = body == null ? "APPROVED" : Objects.toString(body.get("decision"), "");
        if (!decision.equals("APPROVED") && !decision.equals("REJECTED")) {
            return ResponseEntity.badRequest().body(Map.of("error", "审核决定只能是 APPROVED 或 REJECTED"));
        }
        return store.knowledgeDocuments().transition(documentId,version,KnowledgeDocumentVersion.Status.DRAFT,
                value -> decision.equals("APPROVED") ? value.approve(actor.getName(),System.currentTimeMillis())
                        : value.reject(actor.getName(),System.currentTimeMillis()))
                .<Object>map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.status(409).body(Map.of("error", "文档状态已变化")));
    }
    public Object approve(String documentId, int version, Authentication actor) {
        return approve(documentId, version, null, actor);
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

    @PostMapping("/incidents/{incidentId}/retrospectives") public ResponseEntity<?> createRetrospective(
            @PathVariable String incidentId, @RequestBody Map<String, Object> body, Authentication actor) {
        security.require(actor.getName(), Permission.POLICY_ADMIN);
        if (!store.persistent() || !store.durable().healthy()) {
            return ResponseEntity.status(503).body(Map.of("error", "持久知识库未就绪"));
        }
        Incident incident = store.incidents().find(incidentId).orElse(null);
        Session session = incident == null ? null : store.sessions().find(incident.sessionId()).orElse(null);
        if (session == null || session.scope() == null || session.targetNamespace() == null
                || !session.scope().equals(scope()) || !session.targetNamespace().equals(control.resourceNamespace())) {
            return ResponseEntity.notFound().build();
        }
        try {
            String summary = text(body.get("summary"), 1000);
            String rootCause = text(body.get("rootCause"), 2000);
            String resolution = text(body.get("resolution"), 2000);
            String outcome = text(body.get("outcome"), 1000);
            List<String> failureModes = strings(body.get("failureModes"));
            if (failureModes.size() > 40) { throw new IllegalArgumentException("失败经验条目数量过多"); }
            String documentId = "case-" + UUID.randomUUID();
            long createdAt = System.currentTimeMillis();
            var retrospective = new com.rover.agent.core.model.IncidentRetrospective(documentId, incidentId, scope(),
                    text(body.getOrDefault("title", "事件复盘：" + incident.title()), 200), summary, rootCause,
                    resolution, outcome, failureModes, actor.getName(), createdAt,
                    createdAt + Duration.ofDays(90).toMillis());
            if (retrospective.summary().length() + retrospective.rootCause().length()
                    + retrospective.resolution().length() + retrospective.outcome().length()
                    + retrospective.failureModes().stream().mapToInt(String::length).sum() > 39_000) {
                throw new IllegalArgumentException("复盘内容总长度超过限制");
            }
            var draft = store.knowledgeDocuments().createIncidentDraft(retrospective);
            return ResponseEntity.status(HttpStatus.CREATED).body(draft);
        } catch (IllegalArgumentException | IllegalStateException invalid) {
            return ResponseEntity.status(invalid instanceof IllegalStateException ? HttpStatus.CONFLICT : HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", invalid.getMessage()));
        }
    }
    private ResourceScope scope() { return control.resourceScope(); }
    public record RetrospectiveRecord(KnowledgeDocumentVersion document,
                                      com.rover.agent.core.model.IncidentRetrospective retrospective) { }
    public record BatchImportRequest(List<BatchImportItem> documents) { }
    public record BatchImportItem(String documentId, String title, String content, List<String> keywords,
                                  String sourceVersion, Long ttlMillis) { }
    public record BatchImportResult(int index, String documentId, Integer version, String status, String error) { }
    public record BatchImportResponse(int created, int rejected, List<BatchImportResult> results) {
        public BatchImportResponse { results = List.copyOf(results); }
    }
    public record RetrospectiveList(List<RetrospectiveRecord> records, boolean truncated) {
        public RetrospectiveList { records = List.copyOf(records); }
    }
    private static String text(Object value,int max) { if (!(value instanceof String s) || s.isBlank() || s.length()>max) throw new IllegalArgumentException("文档字段无效"); return s.strip(); }
    private static long number(Object value,long fallback) { return value instanceof Number n ? n.longValue() : fallback; }
    private static int length(String value) { return value == null ? 0 : value.length(); }
    private static String optionalText(Object value, int max) {
        if (value == null) { return null; }
        if (!(value instanceof String s) || s.isBlank() || s.length() > max) {
            throw new IllegalArgumentException("复盘来源事件标识无效");
        }
        return s.strip();
    }
    private static List<String> strings(Object value) {
        if (value==null) return List.of(); if (!(value instanceof List<?> list) || list.size()>50) throw new IllegalArgumentException("关键词数量无效");
        return list.stream().map(item -> text(item,100)).toList();
    }
}
