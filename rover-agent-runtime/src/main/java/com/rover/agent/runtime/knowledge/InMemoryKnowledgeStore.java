package com.rover.agent.runtime.knowledge;

import com.rover.agent.core.port.KnowledgeEntry;
import com.rover.agent.core.port.KnowledgeReadPort;
import java.util.Comparator;
import java.util.List;

/**
 * 内存知识库：按关键词命中数排序的轻量检索，回答「怎么配置 / 怎么接入 / 怎么排查」类问题。
 *
 * <p>条目由装配层注入（当前为内置 FAQ 种子）；命中排序只看关键词与词重合，刻意不用向量库——
 * 运维 FAQ 条目少、词表明确，关键词检索即可，后续要上向量/语义检索时替换本实现、不改业务。
 */
public class InMemoryKnowledgeStore implements KnowledgeReadPort {

    private static final int DEFAULT_LIMIT = 5;

    private final List<KnowledgeEntry> entries;

    public InMemoryKnowledgeStore(List<KnowledgeEntry> entries) {
        this.entries = entries == null ? List.of() : List.copyOf(entries);
    }

    @Override
    public List<KnowledgeEntry> search(String query, int limit) {
        String q = query == null ? "" : query.toLowerCase();
        if (q.isBlank()) {
            return List.of();
        }
        int cap = limit <= 0 ? DEFAULT_LIMIT : limit;
        return entries.stream()
                .map(entry -> new Scored(entry, score(entry, q)))
                .filter(scored -> scored.score() > 0)
                .sorted(Comparator.comparingInt(Scored::score).reversed())
                .limit(cap)
                .map(Scored::entry)
                .toList();
    }

    /** 相关度：命中关键词（权重 2）+ 关键词同时命中标题（额外 1）+ 正文词重合（权重 1）。 */
    private static int score(KnowledgeEntry entry, String query) {
        int score = 0;
        for (String keyword : entry.keywords()) {
            String key = keyword.toLowerCase();
            if (query.contains(key)) {
                score += 2;
                if (entry.title() != null && entry.title().toLowerCase().contains(key)) {
                    score += 1;
                }
            }
        }
        String content = entry.content() == null ? "" : entry.content().toLowerCase();
        for (String token : query.split("[\\s，。；,;？?！!、]+")) {
            if (!token.isBlank() && content.contains(token)) {
                score += 1;
            }
        }
        return score;
    }

    private record Scored(KnowledgeEntry entry, int score) {
    }

    /** 内置运维 FAQ 种子：覆盖最常见的「怎么配置 / 怎么接入 / 怎么操作」问题。 */
    public static List<KnowledgeEntry> seedFaq() {
        return List.of(
                new KnowledgeEntry("kb-rate-limit", "怎么配置限流阈值",
                        "在配置页选择 gateway 组件，修改限流相关阈值（如每秒请求数、窗口大小），保存后热更新生效。"
                                + "可在「网关指标」的拒绝计数里确认是否触发限流。",
                        List.of("限流", "限速", "阈值", "rate limit", "并发")),
                new KnowledgeEntry("kb-circuit-breaker", "怎么配置熔断",
                        "在配置页选择 gateway 组件，设置熔断开关、失败阈值与熔断时长，保存后热更新生效。"
                                + "熔断触发后该上游的请求会被快速失败，可在「网关指标」按上游实例观察 5xx 与连接失败。",
                        List.of("熔断", "circuit breaker", "失败阈值", "降级")),
                new KnowledgeEntry("kb-timeout", "怎么配置上游超时",
                        "在配置页选择 gateway 组件，修改连接超时与读超时（毫秒），保存后热更新生效。"
                                + "超时过短会误伤慢上游，建议先看「追踪」里该路径的耗时分布再定值。",
                        List.of("超时", "timeout", "读超时", "连接超时", "延时")),
                new KnowledgeEntry("kb-sample-rate", "怎么调整追踪采样率",
                        "在配置页选择 gateway 组件，修改 trace 采样率（0~1）与慢请求阈值。"
                                + "采样率越高证据越全，但内存占用越大；故障排查时可临时调高。",
                        List.of("采样率", "采样", "sample rate", "trace", "追踪")),
                new KnowledgeEntry("kb-onboard-service", "怎么接入一个后端服务",
                        "先让服务实例向 Nameserver 注册（服务名 + 分组 + 地址端口 + 健康检查），"
                                + "再到路由配置里新增一条路由指向该服务名。接入后可在「实例」页确认健康实例数。",
                        List.of("接入", "注册", "服务", "onboard", "如何接入", "怎么接入")),
                new KnowledgeEntry("kb-gray-release", "怎么做灰度发布",
                        "在同一路由下配置多个版本目标（按 group 区分），再用「调整权重」把新版本的权重从 0 逐步放量。"
                                + "全程走版本化提交与回滚，观察「按上游实例」的窗口指标决定是否继续放量或回滚。",
                        List.of("灰度", "放量", "权重", "金丝雀", "canary", "回滚")),
                new KnowledgeEntry("kb-drain-instance", "怎么摘除异常实例",
                        "确认目标服务后，对该异常实例执行「摘除/下线」即可停止向其转发。"
                                + "摘除是可回滚的，排查完可再「上线/恢复」。操作会生成处置计划，确认后才执行。",
                        List.of("摘除", "下线", "摘掉", "踢掉", "移除", "恢复", "上线")));
    }
}
