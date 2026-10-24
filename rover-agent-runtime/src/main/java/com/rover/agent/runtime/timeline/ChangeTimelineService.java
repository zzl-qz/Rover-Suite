package com.rover.agent.runtime.timeline;

import com.rover.agent.core.model.*;
import com.rover.agent.core.port.RouteControlPort;
import com.rover.agent.core.port.SnapshotUnavailableException;
import com.rover.agent.core.port.TimelineReadPort;
import com.rover.agent.core.timeline.*;
import com.rover.agent.core.timeline.ChangeEvent.Subject;
import com.rover.agent.runtime.repository.AgentStore;
import com.rover.agent.runtime.timeline.JdbcChangeTimeline.*;
import java.time.Clock;
import java.util.*;

/** One Admin's durable event ingestion, poll comparison and historical service association. */
public final class ChangeTimelineService implements TimelineReadPort {
    public static final List<String> POLL_SOURCES = List.of("rover.routes", "rover.instances", "rover.config.gateway", "rover.config.nameserver");
    private final AgentStore store;
    private final RouteControlPort control;
    private final Clock clock;
    private final String runId = UUID.randomUUID().toString();
    private final long pollIntervalMillis;

    public ChangeTimelineService(AgentStore store, RouteControlPort control, long pollIntervalMillis) {
        this(store, control, pollIntervalMillis, Clock.systemUTC());
    }
    public ChangeTimelineService(AgentStore store, RouteControlPort control, long pollIntervalMillis, Clock clock) {
        this.store = store; this.control = control; this.clock = clock;
        this.pollIntervalMillis = Math.max(5_000, pollIntervalMillis);
    }

    public record Release(String sourceEventId, String releaseId, String artifact, String serviceName, String group,
                          String routeId, String businessPrefix, String routeUid, Long generation,
                          String status, Long occurredAtMillis) { }

    /** The authenticated connector identity, scope and control target cannot be chosen in the request. */
    public synchronized AppendResult ingestRelease(String sourcePrincipal, Release request) {
        if (sourcePrincipal == null || sourcePrincipal.isBlank() || request == null) { throw new IllegalArgumentException("缺少发布来源或请求"); }
        String source = "release:" + sourcePrincipal;
        required(source, "来源"); required(request.sourceEventId(), "sourceEventId");
        required(request.releaseId(), "releaseId"); required(request.artifact(), "artifact"); required(request.serviceName(), "serviceName");
        if (!Set.of("STARTED", "SUCCEEDED", "FAILED", "ROLLED_BACK").contains(request.status() == null ? "" : request.status())
                || request.occurredAtMillis() == null || request.occurredAtMillis() <= 0) {
            throw new IllegalArgumentException("发布状态或发生时间不合法");
        }
        Subject subject = new Subject(request.routeId(), request.businessPrefix(), request.routeUid(),
                request.generation() == null ? 0 : request.generation(), List.of(request.serviceName()), request.group(), null, null, null);
        if ((subject.routeUid() == null || subject.routeUid().isBlank()) != (subject.generation() <= 0)) {
            throw new IllegalArgumentException("routeUid 与正 generation 需同时提供");
        }
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("releaseId", request.releaseId()); facts.put("artifact", request.artifact()); facts.put("status", request.status());
        facts.put("sourceClockVerified", false);
        List<String> limits = new ArrayList<>(List.of("发布时间来自外部来源，源时钟未经校准；接收时间不等于发布时间。", "发布与故障时间接近仅为相关线索，不能单独确认根因。"));
        long now = clock.millis(), happened = request.occurredAtMillis();
        if (happened < now - 30L * 24 * 3_600_000 || happened > now + 24L * 3_600_000) {
            throw new IllegalArgumentException("发布源时间需在过去三十天至未来一天内；明显时钟错误应先修正来源");
        }
        if (happened > now + 300_000 || happened < now - 300_000) {
            limits.add("源时间与接收时间相差超过五分钟：可能为迟到、回填或时钟偏差，不自动修正源时间。");
        }
        String id = UUID.randomUUID().toString();
        ChangeEvent event = new ChangeEvent(id, control.resourceScope(), control.controlTargetId(), source,
                request.sourceEventId(), "RELEASE_" + request.status(), happened, now, new TimeRange(happened, happened), subject, facts, limits);
        Map<String, Object> intent = new LinkedHashMap<>(event.json());
        intent.remove("eventId"); intent.remove("observedAtMillis"); intent.remove("limitations");
        String digest = envelope(intent).payloadDigest();
        return repository().append(control.resourceNamespace(), event, digest);
    }

    /** A source must supply a complete snapshot or call unavailable. A failed read never becomes an empty registry. */
    public synchronized void observe(String source, Map<String, Observation> current) {
        if (!POLL_SOURCES.contains(source) || current == null || current.size() > 5000) { throw new IllegalArgumentException("快照来源或容量不合法"); }
        long now = clock.millis();
        var repository = repository();
        String namespace = control.resourceNamespace();
        Baseline previous = repository.baseline(namespace, source);
        boolean hasBaseline = previous != null && previous.observedAt() > 0;
        Map<String, Observation> before = hasBaseline ? previous.resources() : Map.of();
        boolean gap = previous != null && (!previous.available() || !runId.equals(previous.runId())
                || now - previous.observedAt() > pollIntervalMillis * 3 || now < previous.observedAt());
        List<ChangeEvent> events = new ArrayList<>();
        Set<String> keys = new TreeSet<>(before.keySet()); keys.addAll(current.keySet());
        for (String key : keys) {
            Observation old = before.get(key), next = current.get(key);
            if (old != null && next != null && old.subject().equals(next.subject()) && old.values().equals(next.values())) { continue; }
            Subject subject = next == null ? old.subject() : next.subject();
            if (old != null && next != null) {
                Set<String> associatedServices = new TreeSet<>(old.subject().services()); associatedServices.addAll(subject.services());
                subject = new Subject(subject.routeId(), subject.businessPrefix(), subject.routeUid(), subject.generation(),
                        List.copyOf(associatedServices), subject.group(), subject.instanceId(), subject.hostPort(), subject.component());
            }
            Map<String, Object> facts = new LinkedHashMap<>();
            facts.put("before", old == null ? null : old.values()); facts.put("after", next == null ? null : next.values());
            facts.put("sourceRevisionBefore", old == null ? null : old.sourceRevision());
            facts.put("sourceRevisionAfter", next == null ? null : next.sourceRevision());
            facts.put("firstObservation", !hasBaseline); facts.put("observationGap", gap);
            facts.put("effectiveTimeKnown", false);
            List<String> limits = new ArrayList<>(List.of(!hasBaseline
                    ? "首次观测仅建立基线，不代表该资源刚刚发布、注册或发生变更。"
                    : "轮询只证明两次快照不同；实际变更时刻在观测区间内，期间可能发生多次变化。"));
            if (gap) { limits.add("两次成功观测之间发生采集失败、重启或超时空档，无法重建期间所有变化。"); }
            String kind = source.equals("rover.routes") ? "ROUTE" : source.equals("rover.instances") ? "INSTANCE" : "CONFIG";
            kind += !hasBaseline ? "_BASELINE" : next == null ? "_REMOVED" : old == null ? "_APPEARED" : "_CHANGED";
            String id = UUID.randomUUID().toString();
            long from = previous == null || previous.observedAt() <= 0 ? now : Math.min(now, previous.observedAt());
            events.add(new ChangeEvent(id, control.resourceScope(), control.controlTargetId(), source, id,
                    kind, null, now, new TimeRange(from, now), subject, facts, limits));
        }
        long first = previous == null || previous.firstObservedAt() <= 0 ? now : previous.firstObservedAt();
        repository.observation(namespace, source, new Baseline(first, now, now, runId, true,
                (previous == null ? 0 : previous.gapCount()) + (gap && previous.available() ? 1 : 0), Map.copyOf(current)), events);
    }

    public synchronized void unavailable(String source) {
        if (!POLL_SOURCES.contains(source)) { throw new IllegalArgumentException("未知采集源"); }
        var repository = repository();
        Baseline previous = repository.baseline(control.resourceNamespace(), source);
        repository.observation(control.resourceNamespace(), source, new Baseline(previous == null ? 0 : previous.firstObservedAt(),
                previous == null ? 0 : previous.observedAt(), clock.millis(), runId, false,
                (previous == null ? 0 : previous.gapCount()) + (previous == null || previous.available() ? 1 : 0),
                previous == null ? Map.of() : previous.resources()), List.of());
    }

    @Override public TimelineSnapshot query(TimelineQuery query) {
        if (!Objects.equals(control.resourceScope(), query.context().scope())
                || !Objects.equals(control.controlTargetId(), query.context().controlTargetId())
                || !Objects.equals(control.resourceNamespace(), query.context().targetNamespace())) {
            throw new com.rover.agent.core.port.AgentAccess.Denied("时间线资源范围不匹配");
        }
        var repository = repository();
        List<String> limits = new ArrayList<>(List.of("仅展示已持久化事件；轮询可能漏掉周期内的中间变化，空列表不证明没有发布或变更。",
                "服务关联使用历史路由观测，属于观测到的关系；不能据此断言故障根因。"));
        Set<String> services = new TreeSet<>();
        if (query.target().type() == TargetType.ROUTE) {
            List<ChangeEvent> history = repository.routeHistory(control.resourceNamespace(), query.target().value(), query.range().toMillis());
            if (history.size() > 1000) { limits.add("历史路由映射超过 1000 条，关联可能不完整。"); history = history.subList(0, 1000); }
            services.addAll(historicalServices(history, query.range()));
            if (services.isEmpty()) { limits.add("该时间窗没有可验证的历史路由到服务关系；未借用当前路由补造历史服务关联。"); }
            if (services.size() > 100) { throw new SnapshotUnavailableException("历史服务关联超过查询上限"); }
        }
        List<ChangeEvent> rows = repository.query(control.resourceNamespace(), query.target(), query.range(), services, query.limit() + 1);
        boolean truncated = rows.size() > query.limit();
        if (truncated) { rows = rows.subList(0, query.limit()); limits.add("结果达到条数上限，时间线已截断；可缩小查询窗口继续取证。"); }
        rows = rows.stream().map(event -> {
            if (event.occurrenceRange().toMillis() >= query.range().fromMillis() && event.occurrenceRange().fromMillis() <= query.range().toMillis()) { return event; }
            List<String> bounds = new ArrayList<>(event.limitations());
            bounds.add("本事件按接收时间命中，申报发生时间在查询窗口外；不能认定发布在故障窗口内发生。");
            return new ChangeEvent(event.eventId(), event.scope(), event.controlTargetId(), event.sourceId(), event.sourceEventId(),
                    event.kind(), event.occurredAtMillis(), event.observedAtMillis(), event.occurrenceRange(), event.subject(), event.facts(), bounds);
        }).sorted(Comparator.comparingLong((ChangeEvent event) -> event.occurrenceRange().fromMillis())
                .thenComparingLong(ChangeEvent::observedAtMillis).thenComparing(ChangeEvent::eventId)).toList();
        List<Map<String, Object>> sources = repository.sources(control.resourceNamespace());
        for (String source : POLL_SOURCES) {
            var state = sources.stream().filter(s -> source.equals(s.get("sourceId"))).findFirst().orElse(null);
            if (state == null) { limits.add(source + " 尚无采集记录。"); }
            else {
                if (!Boolean.TRUE.equals(state.get("available")) || clock.millis() - ((Number) state.get("lastSuccessAtMillis")).longValue() > pollIntervalMillis * 3) {
                    limits.add(source + " 当前采集失败或已过期。");
                }
                if (((Number) state.get("firstObservedAtMillis")).longValue() > query.range().fromMillis()) { limits.add(source + " 首次成功采集晚于请求窗口起点。"); }
                if (((Number) state.get("gapCount")).longValue() > 0) { limits.add(source + " 曾发生采集空档，不能保证连续历史。"); }
            }
        }
        limits.add("发布来源采用事件推送；未接入或未推送的发布不可见，也不会由版本 group 推断制品。");
        return new TimelineSnapshot(query.range(), clock.millis(), rows, sources, truncated, limits);
    }

    private JdbcChangeTimeline repository() {
        if (!store.persistent()) { throw new SnapshotUnavailableException("时间线要求持久 H2；显式内存模式不提供历史事实"); }
        return store.durable().timeline();
    }

    /** Reconstruct the longest-prefix observation, preserving uncertainty during a detected transition. */
    private static Set<String> historicalServices(List<ChangeEvent> history, TimeRange range) {
        List<ChangeEvent> ordered = history.stream().sorted(Comparator.comparingLong((ChangeEvent e) -> e.occurrenceRange().toMillis())
                .thenComparing(e -> e.kind().endsWith("REMOVED") ? 0 : 1).thenComparing(ChangeEvent::eventId)).toList();
        Map<String, ChangeEvent> state = new HashMap<>();
        Set<String> result = new TreeSet<>();
        for (int start = 0; start < ordered.size();) {
            int end = start + 1;
            while (end < ordered.size() && ordered.get(end).observedAtMillis() == ordered.get(start).observedAtMillis()) { end++; }
            boolean intersects = ordered.subList(start, end).stream().anyMatch(e -> e.occurrenceRange().toMillis() >= range.fromMillis());
            if (intersects) { addWinningServices(state, result); }
            for (ChangeEvent event : ordered.subList(start, end)) {
                String prefix = event.subject().businessPrefix();
                if (event.facts().get("after") == null) {
                    ChangeEvent existing = state.get(prefix);
                    if (existing != null && Objects.equals(existing.subject().routeUid(), event.subject().routeUid())) { state.remove(prefix); }
                } else { state.put(prefix, event); }
            }
            if (intersects) { addWinningServices(state, result); }
            start = end;
        }
        addWinningServices(state, result);
        return result;
    }
    private static void addWinningServices(Map<String, ChangeEvent> state, Set<String> result) {
        state.entrySet().stream().max(Comparator.comparingInt(e -> e.getKey().length())).ifPresent(entry -> {
            if (entry.getValue().facts().get("after") instanceof Map<?, ?> map && map.get("services") instanceof List<?> list) {
                for (Object value : list) { if (value instanceof String service) { result.add(service); } }
            }
        });
    }
    private static void required(String value, String label) {
        if (value == null || value.isBlank() || value.length() > 255 || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(label + " 必须为 1~255 字符且不能含控制字符");
        }
    }
    private static EvidenceEnvelope envelope(Map<String, Object> facts) {
        return new EvidenceEnvelope(1, "timeline", "EVENT", "", "timeline-v1", null, null, null, 1,
                null, "SOURCE_EVENTS", 1, null, null, List.of(), facts, List.of(), null, null, "timeline-allowlist-v1");
    }
}
