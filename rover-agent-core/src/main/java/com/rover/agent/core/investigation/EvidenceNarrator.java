package com.rover.agent.core.investigation;

import com.rover.agent.core.port.LogEntry;
import com.rover.agent.core.snapshot.ConfigEntrySnapshot;
import com.rover.agent.core.snapshot.DiscoveryMode;
import com.rover.agent.core.snapshot.GatewayMetricSnapshot;
import com.rover.agent.core.snapshot.InstanceSnapshot;
import com.rover.agent.core.snapshot.RegistryEventSnapshot;
import com.rover.agent.core.snapshot.RouteSnapshot;
import com.rover.agent.core.snapshot.RouteUpstreamSnapshot;
import com.rover.agent.core.snapshot.TraceRow;
import com.rover.agent.core.snapshot.TraceSnapshot;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 证据表述：证据内容、判断边界与读取失败说明的唯一出处，保证各数据源的口径一致。 */
public final class EvidenceNarrator {

    /**
     * 指标的统计窗口：与 Gateway 的 {@code /_api/live?range=60} 同口径。
     *
     * 该常量是本项目「指标证据窗口」的唯一出处，能力执行与状态查询都引用它，避免出现两套窗口长度。
     */
    public static final int METRIC_WINDOW_SECONDS = 60;

    /** 事件与追踪共用的判定窗口：最近五分钟。 */
    public static final int EVENT_WINDOW_SECONDS = 300;

    /** 配置证据里最多列举的条目数：证据要能复核，但不能把整张配置表塞进结论。 */
    private static final int MAX_CONFIG_SAMPLE = 8;

    /** 事件证据里最多列举的条数。 */
    private static final int MAX_EVENT_SAMPLE = 5;

    /** 历史日志证据里最多列举的条数。 */
    private static final int MAX_LOG_SAMPLE = 8;

    /** 历史日志单条 payload 的最大展示长度，超出截断，避免把结论塞满。 */
    private static final int LOG_PAYLOAD_LIMIT = 160;

    private EvidenceNarrator() { }

    /** 路由快照的证据表述；{@code route} 为 {@code null} 表示当前路由表没有匹配项。 */
    public static EvidenceNarration route(RouteSnapshot route) {
        if (route == null) {
            return new EvidenceNarration("当前路由表没有匹配项", List.of());
        }
        String targets = staticTargets(route);
        String detail = "id=" + text(route.routeId()) + "，前缀=" + text(route.businessPrefix())
                + "，服务=" + text(route.serviceName()) + "，分组=" + routeGroupLabel(route.group())
                + (targets.isBlank() ? "" : "，静态目标=" + targets);
        // 两种上游都没有时把「没有配」说成事实：只留空字段会让读的人以为信息缺失，
        // 进而把一条能转发的路由判成配错了。
        List<String> limitations = new ArrayList<>();
        if (text(route.serviceName()).isBlank() && targets.isBlank()) {
            limitations.add("该路由既没有目标服务也没有静态上游地址，请求匹配到它也无法转发。");
        }
        return new EvidenceNarration(detail, List.copyOf(limitations));
    }

    /** 静态上游地址：单个 targetUrl 与多地址 targetUrls 取有值的一个。 */
    private static String staticTargets(RouteSnapshot route) {
        String multiple = text(route.targetUrls());
        return multiple.isBlank() ? text(route.targetUrl()) : multiple;
    }

    /** 路由清单的证据表述：逐条给出前缀与上游，「一共有几条路由」这类问题靠它回答。 */
    public static EvidenceNarration routes(List<RouteSnapshot> all) {
        if (all == null || all.isEmpty()) {
            return new EvidenceNarration("Gateway 当前没有配置任何路由", List.of(), 0, 0);
        }
        List<String> lines = new ArrayList<>();
        for (RouteSnapshot route : all) {
            String targets = staticTargets(route);
            String upstream;
            if (!text(route.serviceName()).isBlank()) {
                upstream = "服务=" + text(route.serviceName()) + "，分组=" + routeGroupLabel(route.group());
            } else if (!targets.isBlank()) {
                upstream = "静态目标=" + targets;
            } else {
                upstream = "无上游（匹配到也无法转发）";
            }
            lines.add(text(route.businessPrefix()) + "（id=" + text(route.routeId()) + "）→ " + upstream);
        }
        return new EvidenceNarration("共 " + all.size() + " 条路由：" + String.join("；", lines),
                List.of(), all.size(), 0);
    }

    /** 上游发现模式的证据表述；未知模式会带出「实例数据不能用于判断上游」的判断边界。 */
    public static EvidenceNarration discoveryMode(DiscoveryMode mode) {
        DiscoveryMode value = mode == null ? DiscoveryMode.UNKNOWN : mode;
        return new EvidenceNarration("Gateway 服务发现模式=" + value,
                value == DiscoveryMode.UNKNOWN
                        ? List.of("Gateway 服务发现模式未知，不能用 Nameserver 实例推断上游状态。")
                        : List.of());
    }

    /** 实例快照的证据表述；有目标路由时额外给出该目标的健康实例数。 */
    public static EvidenceNarration instances(List<InstanceSnapshot> instances, RouteSnapshot route) {
        String service = route == null ? "" : text(route.serviceName());
        String group = route == null ? "" : text(route.group());
        return instances(instances, service, group);
    }

    /**
     * 实例快照的证据表述（按服务/分组口径）。
     *
     * 「这个服务没注册」与「这个服务注册了但没有健康实例」必须分开说：前者是查错了对象，
     * 后者才是一个需要处置的故障。混为一谈时，读的人（和模型）会把一个不存在的服务
     * 当成一个挂掉的服务，并给出一个确定的错误结论。
     */
    public static EvidenceNarration instances(List<InstanceSnapshot> instances, String serviceName, String group) {
        List<InstanceSnapshot> rows = instances == null ? List.of() : instances;
        String service = text(serviceName);
        String groupName = text(group);
        if (service.isBlank()) {
            return new EvidenceNarration("注册实例共 " + rows.size() + " 个；样本：" + sample(rows),
                    List.of(), rows.size(), 0);
        }
        List<InstanceSnapshot> matched = rows.stream()
                .filter(item -> service.equals(text(item.serviceName())))
                .filter(item -> groupName.isBlank() || groupName.equals(text(item.group())))
                .toList();
        if (matched.isEmpty()) {
            return new EvidenceNarration("注册中心没有服务 " + service + " 的任何实例；当前已注册的服务："
                            + registeredServices(rows),
                    List.of("注册中心不存在该服务名，请确认名称拼写或该服务是否已上线。"), rows.size(), 0);
        }
        long healthy = matched.stream().filter(InstanceSnapshot::healthy).count();
        return new EvidenceNarration("目标 " + service + "/" + routeGroupLabel(groupName) + " 的注册实例 "
                + matched.size() + " 个，其中健康 " + healthy + " 个；样本：" + sample(matched),
                List.of(), matched.size(), 0);
    }

    /** 实例样本行：服务/分组@地址:端口(健康状态)，最多 5 条。 */
    private static String sample(List<InstanceSnapshot> rows) {
        return rows.stream().limit(5)
                .map(item -> text(item.serviceName()) + "/" + groupLabel(item.group()) + "@" + text(item.host())
                        + ":" + item.port() + "(" + (item.healthy() ? "健康" : "不健康") + ")")
                .reduce((left, right) -> left + "、" + right).orElse("无");
    }

    /** 当前注册的全部服务名；用于告诉调用方「有哪些服务可查」，而不是让它对着空结果猜。 */
    private static String registeredServices(List<InstanceSnapshot> rows) {
        return rows.stream().map(item -> text(item.serviceName())).filter(name -> !name.isBlank())
                .distinct().reduce((left, right) -> left + "、" + right).orElse("无");
    }

    /**
     * 指定服务与分组的健康实例数：状态查询与调查证据共用同一份计数口径。
     *
     * {@code group} 为空表示不限分组（路由未指定分组时即此语义）。
     */
    public static long healthyCount(List<InstanceSnapshot> instances, String serviceName, String group) {
        List<InstanceSnapshot> rows = instances == null ? List.of() : instances;
        String service = text(serviceName);
        String groupName = text(group);
        if (service.isBlank()) {
            return 0;
        }
        return rows.stream()
                .filter(item -> service.equals(text(item.serviceName())))
                .filter(item -> groupName.isBlank() || groupName.equals(text(item.group())))
                .filter(InstanceSnapshot::healthy)
                .count();
    }

    /** 指标快照的证据表述；样本量即窗口内请求数。 */
    public static EvidenceNarration metrics(GatewayMetricSnapshot metric) {
        return new EvidenceNarration("最近窗口请求数=" + metric.windowRequests() + "，5xx=" + metric.status5xx()
                + "，全局无上游拒绝累计=" + metric.noUpstreamRejects(),
                List.of("无上游拒绝数是全局累计值，不能单独归因到该路径。"),
                metric.windowRequests(), METRIC_WINDOW_SECONDS);
    }

    /**
     * 「路由 × 上游实例」窗口观测的证据表述。
     *
     * 样本量即该实例在窗口内的请求数。样本不足时只报事实、不给出「健康 / 异常」的倾向性判断——
     * 一两个请求里的 5xx 与几百个请求里的 5xx 不是同一件事，这里必须把差异说清楚。
     */
    public static EvidenceNarration routeUpstream(RouteUpstreamSnapshot row) {
        String detail = "上游 " + text(row.hostPort()) + "：窗口请求数=" + row.windowRequests()
                + "，5xx=" + row.status5xx() + "，连接失败=" + row.connectFail() + "，超时=" + row.timeout()
                + "，平均耗时=" + row.avgMillis() + "ms，P95=" + row.p95Millis() + "ms";
        List<String> limitations = new ArrayList<>();
        if (row.windowRequests() < InvestigationRules.MIN_INSTANCE_SAMPLE) {
            limitations.add("上游 " + text(row.hostPort()) + " 窗口请求数 " + row.windowRequests()
                    + " 低于判断阈值 " + InvestigationRules.MIN_INSTANCE_SAMPLE + "，样本不足，无法判断该实例是否异常。");
        }
        limitations.add("该观测只覆盖最近 " + row.windowSeconds()
                + " 秒；停止流量后窗口内没有新样本，历史观测不能证明异常已恢复。");
        return new EvidenceNarration(detail, List.copyOf(limitations), row.windowRequests(), row.windowSeconds());
    }

    /** 「路由 × 上游实例」窗口内没有转发记录时的证据表述：这是「没有样本」，不是「数据不可用」。 */
    public static EvidenceNarration routeUpstreamsEmpty(String routeId, int windowSeconds) {
        return new EvidenceNarration("路由 " + text(routeId) + " 最近 " + windowSeconds
                + " 秒没有上游转发记录，无法按实例归因",
                List.of("窗口内没有样本，无法判断该路由任何上游实例的状态；也不能据此判定异常已恢复。"),
                0, windowSeconds);
    }

    /**
     * 单个上游实例观测的单行事实：查询回答与假设判定共用同一套数字口径。
     *
     * 只报事实（请求数、5xx、错误率），是否算异常由调用方结合样本量判断。
     */
    public static String upstreamFact(RouteUpstreamSnapshot row) {
        return "上游 " + text(row.hostPort()) + "（窗口请求 " + row.windowRequests() + " 次，5xx "
                + row.status5xx() + " 次，错误率 " + String.format(Locale.ROOT, "%.1f", errorRate(row)) + "%）";
    }

    /** 窗口内 5xx 的错误率（百分比）；没有样本时记 0，由调用方按「样本不足」处理。 */
    public static double errorRate(RouteUpstreamSnapshot row) {
        return row.windowRequests() == 0 ? 0.0 : row.status5xx() * 100.0 / row.windowRequests();
    }

    /**
     * 配置快照的证据表述（按组件聚合）。
     *
     * 配置是时点事实：它说明「生效配置是什么」，不能说明运行态是否已按新配置工作。
     */
    public static EvidenceNarration configs(String component, List<ConfigEntrySnapshot> entries) {
        List<ConfigEntrySnapshot> rows = entries == null ? List.of() : entries;
        long unavailable = rows.stream().filter(ConfigEntrySnapshot::unavailable).count();
        String sample = rows.stream().limit(MAX_CONFIG_SAMPLE)
                .map(item -> text(item.key()) + "=" + text(item.value())
                        + "（应用方式=" + text(item.applyMode())
                        + (item.hotReloadable() ? "，支持热更新" : "，不支持热更新") + "）")
                .reduce((left, right) -> left + "、" + right).orElse("无");
        String detail = "组件 " + text(component) + " 生效配置 " + rows.size() + " 项"
                + (unavailable == 0 ? "" : "（其中 " + unavailable + " 项取值读取失败）") + "；样本：" + sample;
        List<String> limitations = new ArrayList<>();
        limitations.add("配置快照只反映当前生效值，不代表运行态已按该配置工作；改动是否生效要看对应流量与错误指标。");
        if (unavailable > 0) {
            limitations.add("有 " + unavailable + " 项配置取值读取失败，判断时不能把这些项当作默认值。");
        }
        return new EvidenceNarration(detail, List.copyOf(limitations), rows.size(), 0);
    }

    /**
     * 注册中心事件的证据表述：只列举最近 {@link #EVENT_WINDOW_SECONDS} 秒内的事件。
     *
     * 事件说明「注册状态什么时候变了」，不能替代实例快照，也不能证明网关已经按新状态转发。
     */
    public static EvidenceNarration events(List<RegistryEventSnapshot> events, long nowMillis) {
        List<RegistryEventSnapshot> rows = events == null ? List.of() : events;
        long now = nowMillis > 0 ? nowMillis : System.currentTimeMillis();
        long windowMillis = EVENT_WINDOW_SECONDS * 1000L;
        List<String> recent = new ArrayList<>();
        for (RegistryEventSnapshot row : rows) {
            if (!isWithin(row.timestampMillis(), now, windowMillis)) {
                continue;
            }
            recent.add(text(row.type()) + " " + text(row.serviceName()) + "/" + text(row.instanceId())
                    + "：" + text(row.detail()));
        }
        long outside = rows.size() - recent.size();
        String detail = "最近 " + EVENT_WINDOW_SECONDS + " 秒注册中心事件 " + recent.size() + " 条"
                + (outside == 0 ? "" : "（另有 " + outside + " 条更早事件，不计入判定）")
                + (recent.isEmpty() ? "" : "；最近事件："
                        + String.join("、", recent.subList(Math.max(0, recent.size() - MAX_EVENT_SAMPLE), recent.size())));
        List<String> limitations = new ArrayList<>();
        limitations.add("事件来自注册中心，只说明注册状态变化，不能证明 Gateway 已经按新状态转发请求。");
        limitations.add("事件缓冲区容量有限，事件少不等于没有发生上下线。");
        return new EvidenceNarration(detail, List.copyOf(limitations), recent.size(), EVENT_WINDOW_SECONDS);
    }

    /**
     * 追踪快照的证据表述：只统计与目标路径精确匹配、且落在最近五分钟判定窗口内的记录。
     *
     * 缓冲区里更早的记录单独说明条数而不计入判定，避免出现「证据里列着 503、结论说没采到」的自相矛盾。
     */
    public static EvidenceNarration traces(TraceSnapshot snapshot, String path) {
        List<TraceRow> rows = snapshot.rows() == null ? List.of() : snapshot.rows();
        long now = snapshot.observedAtMillis() > 0 ? snapshot.observedAtMillis() : System.currentTimeMillis();
        List<String> recent = new ArrayList<>();
        int matched = 0;
        int earlier = 0;
        for (TraceRow row : rows) {
            if (!text(path).equals(text(row.path()))) {
                continue;
            }
            if (!InvestigationRules.isRecent(now, row)) {
                earlier++;
                continue;
            }
            matched++;
            if (recent.size() < 5) {
                recent.add("HTTP " + row.statusCode() + " traceId=" + text(row.traceId()));
            }
        }
        String detail = (snapshot.enabled() ? "" : "追踪已关闭；") + "最近五分钟精确路径匹配追踪 " + matched
                + " 条" + (earlier == 0 ? "" : "（缓冲区另有 " + earlier + " 条更早记录，不计入判定）")
                + "，采样率=" + snapshot.sampleRate()
                + (recent.isEmpty() ? "" : "；最近记录：" + String.join("、", recent));
        List<String> limitations = new ArrayList<>();
        if (!snapshot.enabled()) {
            limitations.add("Gateway 追踪已关闭，当前无法采集新的请求记录。");
        }
        limitations.add("追踪受采样与缓冲容量影响；历史记录不能证明当前配置导致失败，没有记录也不能证明没有请求。");
        return new EvidenceNarration(detail, List.copyOf(limitations), matched, EVENT_WINDOW_SECONDS);
    }

    public static String routeUnavailable() {
        return "Gateway 路由数据不可用，无法确认路径命中的路由。";
    }

    public static String instancesUnavailable() {
        return "Nameserver 实例数据不可用，无法确认上游健康状态。";
    }

    public static String metricsUnavailable() {
        return "Gateway 实时指标不可用。";
    }

    public static String tracesUnavailable() {
        return "Gateway 追踪数据不可用。";
    }

    public static String configsUnavailable() {
        return "Gateway 配置快照不可用，无法确认当前生效配置。";
    }

    public static String eventsUnavailable() {
        return "Nameserver 注册事件不可用，无法确认实例的上下线经过。";
    }

    public static String logsUnavailable() {
        return "落盘历史日志不可用，无法确认配置变更、实例事件与错误经过。";
    }

    /**
     * 历史日志的证据表述：按时间倒序列举最近 {@value #MAX_LOG_SAMPLE} 条，payload 截断。
     *
     * @param types 类型过滤（如 CONFIG_CHANGE / ROLLBACK / ERROR / INSTANCE_EVENT）；空表示全部类型
     * @param fromMillis 起始毫秒时间戳；{@code <=0} 表示不限
     * @param toMillis   截止毫秒时间戳；{@code <=0} 表示不限
     */
    public static EvidenceNarration logs(List<LogEntry> entries, List<String> types, long fromMillis,
                                         long toMillis) {
        List<LogEntry> rows = entries == null ? List.of() : entries;
        List<String> limitations = new ArrayList<>();
        String typeText = types == null || types.isEmpty() ? "全部类型" : String.join("、", types);
        long windowSeconds = windowSeconds(fromMillis, toMillis);
        if (rows.isEmpty()) {
            limitations.add("查询范围内没有 " + typeText + " 的历史日志记录。");
            return new EvidenceNarration("没有 " + typeText + " 的历史日志记录", List.copyOf(limitations),
                    0, (int) windowSeconds);
        }
        List<String> lines = new ArrayList<>();
        for (LogEntry entry : rows.stream().limit(MAX_LOG_SAMPLE).toList()) {
            lines.add(stamp(entry.ts()) + " " + entry.type() + " " + text(entry.target())
                    + "：" + truncate(entry.payload(), LOG_PAYLOAD_LIMIT));
        }
        String detail = "共 " + rows.size() + " 条" + typeText + "历史日志：" + String.join("；", lines);
        if (rows.size() > MAX_LOG_SAMPLE) {
            limitations.add("历史日志共 " + rows.size() + " 条，只列举最近的 " + MAX_LOG_SAMPLE + " 条。");
        }
        return new EvidenceNarration(detail, List.copyOf(limitations), rows.size(), (int) windowSeconds);
    }

    /** 按上游实例的指标取数失败：与「窗口内没有样本」严格区分。 */
    public static String routeUpstreamsUnavailable(String routeId) {
        return "路由 " + text(routeId) + " 的按上游实例指标不可用，无法确认是哪台实例异常。";
    }

    /** 记录时间是否落在判定窗口内；时间戳缺失或晚于当前时刻的记录一律不计入。 */
    private static boolean isWithin(long timestampMillis, long nowMillis, long windowMillis) {
        return timestampMillis > 0 && timestampMillis <= nowMillis && nowMillis - timestampMillis <= windowMillis;
    }

    private static String groupLabel(String group) {
        return text(group).isBlank() ? "默认组" : text(group);
    }

    private static String routeGroupLabel(String group) {
        return text(group).isBlank() ? "全部分组" : text(group);
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }

    /** 时间范围换算成窗口秒数；无有效范围时为 0（非窗口口径）。 */
    private static long windowSeconds(long fromMillis, long toMillis) {
        if (fromMillis <= 0 || toMillis <= 0 || toMillis <= fromMillis) {
            return 0;
        }
        return (toMillis - fromMillis) / 1000L;
    }

    private static String stamp(long millis) {
        if (millis <= 0) {
            return "";
        }
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"));
    }

    private static String truncate(String value, int limit) {
        String v = value == null ? "" : value.trim();
        return v.length() <= limit ? v : v.substring(0, limit) + "…";
    }
}