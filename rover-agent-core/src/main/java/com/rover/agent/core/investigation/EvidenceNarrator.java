package com.rover.agent.core.investigation;

import com.rover.agent.core.snapshot.DiscoveryMode;
import com.rover.agent.core.snapshot.GatewayMetricSnapshot;
import com.rover.agent.core.snapshot.InstanceSnapshot;
import com.rover.agent.core.snapshot.RouteSnapshot;
import com.rover.agent.core.snapshot.TraceRow;
import com.rover.agent.core.snapshot.TraceSnapshot;
import java.util.ArrayList;
import java.util.List;

/** 证据表述：证据内容、判断边界与读取失败说明的唯一出处，保证各数据源的口径一致。 */
public final class EvidenceNarrator {

    private EvidenceNarrator() { }

    /** 路由快照的证据表述；{@code route} 为 {@code null} 表示当前路由表没有匹配项。 */
    public static EvidenceNarration route(RouteSnapshot route) {
        if (route == null) {
            return new EvidenceNarration("当前路由表没有匹配项", List.of());
        }
        String targetUrl = text(route.targetUrl());
        String detail = "id=" + text(route.routeId()) + "，前缀=" + text(route.businessPrefix())
                + "，服务=" + text(route.serviceName()) + "，分组=" + routeGroupLabel(route.group())
                + (targetUrl.isBlank() ? "" : "，静态目标=" + targetUrl);
        return new EvidenceNarration(detail, List.of());
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
     * 能力执行器不持有路由对象（能力之间彼此独立），因此这里以服务名与分组作为口径入参，
     * 与按路由对象取数的重载共用同一份表述逻辑。
     */
    public static EvidenceNarration instances(List<InstanceSnapshot> instances, String serviceName, String group) {
        List<InstanceSnapshot> rows = instances == null ? List.of() : instances;
        String sample = rows.stream().limit(5)
                .map(item -> text(item.serviceName()) + "/" + groupLabel(item.group()) + "@" + text(item.host())
                        + ":" + item.port() + "(" + (item.healthy() ? "健康" : "不健康") + ")")
                .reduce((left, right) -> left + "、" + right).orElse("无");
        String service = text(serviceName);
        String groupName = text(group);
        if (service.isBlank()) {
            return new EvidenceNarration("注册实例共 " + rows.size() + " 个；样本：" + sample, List.of());
        }
        long healthy = healthyCount(rows, service, groupName);
        return new EvidenceNarration("目标 " + service + "/" + routeGroupLabel(groupName) + " 的健康实例 " + healthy
                + " 个；注册实例共 " + rows.size() + " 个；样本：" + sample, List.of());
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

    /** 指标快照的证据表述。 */
    public static EvidenceNarration metrics(GatewayMetricSnapshot metric) {
        return new EvidenceNarration("最近窗口请求数=" + metric.windowRequests() + "，5xx=" + metric.status5xx()
                + "，全局无上游拒绝累计=" + metric.noUpstreamRejects(),
                List.of("无上游拒绝数是全局累计值，不能单独归因到该路径。"));
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
        return new EvidenceNarration(detail, List.copyOf(limitations));
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

    private static String groupLabel(String group) {
        return text(group).isBlank() ? "默认组" : text(group);
    }

    private static String routeGroupLabel(String group) {
        return text(group).isBlank() ? "全部分组" : text(group);
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}