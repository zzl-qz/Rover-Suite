package com.rover.agent.core.investigation;

import com.rover.agent.core.model.Confidence;
import com.rover.agent.core.model.Hypothesis;
import com.rover.agent.core.model.Verdict;
import com.rover.agent.core.snapshot.DiscoveryMode;
import com.rover.agent.core.snapshot.InstanceSnapshot;
import com.rover.agent.core.snapshot.RouteSnapshot;
import com.rover.agent.core.snapshot.TraceRow;
import com.rover.agent.core.snapshot.TraceSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 假设驱动的结论合成：按已采集的只读事实逐条确认或排除候选故障原因，
 * 并且只依据验证结果给出结论与置信度。
 *
 * 纯函数，不依赖模型、Spring 或具体数据源，因此上述判定可以被直接单测覆盖。
 */
public final class InvestigationRules {

    private static final String SOURCE_ROUTES = "/api/routes";
    private static final String SOURCE_INSTANCES = "/api/instances";
    private static final String SOURCE_TRACES = "/api/traces";
    /** H5 的假设陈述保持中性：只描述可观察到的路径状态码，不指向任何一侧。 */
    private static final String H5_STATEMENT = "该路径近期返回 503";
    /**
     * 追踪记录只对最近五分钟内的判定有价值。
     *
     * 证据表述与假设判定共用这一个窗口，否则会出现「证据里列着 503、结论说没采到」的自相矛盾。
     */
    static final long RECENT_TRACE_WINDOW_MILLIS = TimeUnit.MINUTES.toMillis(5);

    private InvestigationRules() { }

    public static Findings evaluate(FindingsInput input) {
        RouteSnapshot route = input.route();
        DiscoveryMode discovery = input.discoveryMode() == null ? DiscoveryMode.UNKNOWN : input.discoveryMode();
        List<InstanceSnapshot> instances = input.instances();
        TraceSnapshot traces = input.traces();

        List<Hypothesis> hypotheses = new ArrayList<>();
        String summary;
        Confidence confidence = Confidence.LOW;
        boolean evaluateDownstream = false;
        if (route == null) {
            hypotheses.add(hypothesis("H1", "该路径未命中任何路由",
                    input.routeRead() ? Verdict.CONFIRMED : Verdict.UNKNOWN,
                    input.routeRead() ? "当前路由表没有匹配项" : "路由数据不可用，无法验证",
                    List.of(SOURCE_ROUTES)));
            summary = input.routeRead() ? "当前路由表没有匹配该路径的路由。" : "路由数据不足，暂时无法判断请求失败原因。";
            confidence = input.routeRead() ? Confidence.HIGH : Confidence.LOW;
        } else {
            hypotheses.add(hypothesis("H1", "该路径未命中任何路由", Verdict.REJECTED,
                    "已命中路由 " + text(route.businessPrefix()), List.of(SOURCE_ROUTES)));
            // 静态上游与非 Nameserver 服务发现都属于路由事实（判断范围），不是故障假设：
            // 它们只写进结论与证据，不能作为「已确认假设」展示。
            String service = text(route.serviceName());
            if (service.isBlank()) {
                summary = "该路由配置为静态上游（无动态服务目标）；当前只读数据无法确认其上游状态。";
            } else if (discovery != DiscoveryMode.NAMESERVER) {
                summary = "该路由指向 " + service + "，Gateway 使用 " + discovery
                        + " 服务发现；Nameserver 实例数据不能用于判断该路由的上游状态。";
            } else if (instances == null) {
                hypotheses.add(hypothesis("H3", "目标服务当前没有匹配实例", Verdict.UNKNOWN,
                        "实例数据不可用，无法验证", List.of(SOURCE_INSTANCES)));
                summary = "已找到路由，但实例数据不足，暂时无法判断上游状态。";
            } else {
                evaluateDownstream = true;
                String group = text(route.group());
                List<InstanceSnapshot> matching = instances.stream()
                        .filter(item -> service.equals(text(item.serviceName())))
                        .filter(item -> group.isBlank() || group.equals(text(item.group())))
                        .toList();
                long healthy = matching.stream().filter(InstanceSnapshot::healthy).count();
                if (matching.isEmpty()) {
                    hypotheses.add(hypothesis("H3", "目标服务当前没有匹配实例", Verdict.CONFIRMED,
                            "注册表中没有 " + service + " / " + routeGroupLabel(group) + " 的实例",
                            List.of(SOURCE_INSTANCES)));
                    summary = "路由 " + text(route.businessPrefix()) + " 指向 " + service + " / "
                            + routeGroupLabel(group) + "，当前 Nameserver 注册表没有匹配实例。"
                            + missingTraceClause(traces, input.path());
                    confidence = Confidence.MEDIUM;
                } else if (healthy == 0) {
                    hypotheses.add(hypothesis("H3", "目标服务当前没有匹配实例", Verdict.REJECTED,
                            "已找到 " + matching.size() + " 个匹配实例", List.of(SOURCE_INSTANCES)));
                    hypotheses.add(hypothesis("H4", "匹配实例均不健康", Verdict.CONFIRMED,
                            matching.size() + " 个实例均标为不健康", List.of(SOURCE_INSTANCES)));
                    summary = "路由 " + text(route.businessPrefix()) + " 的目标当前有 " + matching.size()
                            + " 个注册实例，但均标为不健康；Gateway 在没有健康实例时可能回退使用这些实例。";
                } else {
                    hypotheses.add(hypothesis("H3", "目标服务当前没有匹配实例", Verdict.REJECTED,
                            "已找到 " + matching.size() + " 个匹配实例", List.of(SOURCE_INSTANCES)));
                    hypotheses.add(hypothesis("H4", "匹配实例均不健康", Verdict.REJECTED,
                            "其中 " + healthy + " 个健康", List.of(SOURCE_INSTANCES)));
                    summary = "该路由当前有 " + healthy + " 个匹配的健康实例；现有数据不足以确定请求失败原因。";
                }
            }
        }
        if (evaluateDownstream) {
            hypotheses.add(downstreamTraceHypothesis(traces, input.path()));
        }
        return new Findings(List.copyOf(hypotheses), summary, confidence);
    }

    /**
     * H5 只判定「该路径近期是否返回 503」这一可观察事实，结论只有「确认」与「无法验证」两种。
     *
     * 没有追踪数据、追踪已关闭、或最近五分钟未采到该 503 时，都只能判定为无法验证：
     * 采样与缓冲意味着「没采到」既不能证明没有请求，也不能排除同段时间内曾出现过 503。
     * 确认时不推断 503 的来源，因为该状态码也可能由 Gateway 自身产生。
     */
    private static Hypothesis downstreamTraceHypothesis(TraceSnapshot traces, String path) {
        if (traces == null) {
            return hypothesis("H5", H5_STATEMENT, Verdict.UNKNOWN, "追踪数据不可用，无法验证", List.of());
        }
        if (!traces.enabled()) {
            return hypothesis("H5", H5_STATEMENT, Verdict.UNKNOWN, "Gateway 追踪已关闭，无法验证",
                    List.of(SOURCE_TRACES));
        }
        if (hasRecent503(traces, path)) {
            return hypothesis("H5", H5_STATEMENT, Verdict.CONFIRMED,
                    "最近五分钟追踪记录到该路径返回 503；仅凭状态码无法判定它由 Gateway 还是上游产生",
                    List.of(SOURCE_TRACES));
        }
        return hypothesis("H5", H5_STATEMENT, Verdict.UNKNOWN,
                hasRecentTrace(traces, path)
                        ? "最近五分钟采到该路径的请求记录，但未出现 503；追踪有采样与容量限制，不能据此排除该路径曾返回 503"
                        : "最近五分钟未采到该路径的请求记录，无法验证",
                List.of(SOURCE_TRACES));
    }

    /**
     * 目标服务没有匹配实例时，补充一句「现在还能不能采到失败证据」的口径：
     * 追踪已关闭时不能说「尚缺少证据」，否则会把「采集不了」写成「暂时没发现」。
     */
    private static String missingTraceClause(TraceSnapshot traces, String path) {
        if (traces != null && !traces.enabled()) {
            return "Gateway 追踪已关闭，无法采集该路径的失败请求证据。";
        }
        return hasRecent503(traces, path)
                ? "该路径最近五分钟的追踪还记录了 503，但无法仅凭状态码确定其原因。"
                : "尚缺少该路径近期的失败请求证据。";
    }

    /** 假设验证结果的计数说明，用于调查步骤进度。 */
    public static String describeVerdicts(List<Hypothesis> hypotheses) {
        long confirmed = hypotheses.stream().filter(item -> item.status() == Verdict.CONFIRMED).count();
        long rejected = hypotheses.stream().filter(item -> item.status() == Verdict.REJECTED).count();
        long unknown = hypotheses.stream().filter(item -> item.status() == Verdict.UNKNOWN).count();
        return "确认 " + confirmed + " 项 / 排除 " + rejected + " 项 / 无法验证 " + unknown + " 项";
    }

    private static Hypothesis hypothesis(String id, String statement, Verdict status, String detail,
                                         List<String> sources) {
        return new Hypothesis(id, statement, status, detail, sources);
    }

    /** 最近五分钟是否采到与该路径精确匹配的请求记录。 */
    private static boolean hasRecentTrace(TraceSnapshot traces, String path) {
        if (traces == null || traces.rows() == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        for (TraceRow row : traces.rows()) {
            if (isRecent(now, row) && text(path).equals(text(row.path()))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasRecent503(TraceSnapshot traces, String path) {
        if (traces == null || traces.rows() == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        for (TraceRow row : traces.rows()) {
            if (isRecent(now, row) && row.statusCode() == 503 && text(path).equals(text(row.path()))) {
                return true;
            }
        }
        return false;
    }

    /** 该记录是否落在最近五分钟的判定窗口内；证据表述复用同一判定。 */
    static boolean isRecent(long now, TraceRow row) {
        return row.startMillis() > 0 && row.startMillis() <= now
                && now - row.startMillis() <= RECENT_TRACE_WINDOW_MILLIS;
    }

    private static String routeGroupLabel(String group) {
        return group.isBlank() ? "全部分组" : group;
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}