package com.rover.agent.core.planning;

import java.util.List;

/**
 * 调查提示词探测：只回答「问题里有没有点到配置或事件」。
 *
 * <b>它是提示，不是裁决。</b>既不分类问题、也不决定最终查什么：
 * <ul>
 *   <li>它只说「这个方向值得规划器多看一眼」，两个方向各自判断、互不排斥；</li>
 *   <li>它不会因为命中了某一类就屏蔽另一类（问「限流阈值是不是改了」时指标与配置都该看）；</li>
 *   <li>它不参与对话主路径——人工提问查什么由模型在对话中决定。</li>
 * </ul>
 *
 * 它唯一的用途是 {@link RuleBasedPlanner} 决定要不要追加可选的 CONFIG_READ / EVENT_QUERY 步骤：
 * 这两类事实对常见故障没有普遍判定价值，问题里不问就不读，避免每次调查凭空多两跳只读调用、
 * 也让证据里不混进与问题无关的内容。代价上限是一跳只读调用，因此宁可多提示一个方向，
 * 也不要为了"少查一次"把真正的根因方向挡在门外。
 *
 * 词表刻意收「明确在问那类事实」的说法，不收裸词：问「注册实例有几个」问的是当前状态，
 * 只有提到「事件 / 上下线 / 剔除 / 注销」才是问变更经过；「变更」收，但「注册」不收。
 */
public final class InvestigationCueDetector {

    private static final List<String> CONFIG_CUES = List.of("配置", "限流", "熔断", "阈值", "限速", "并发", "采样率",
            "超时");

    /** 事件词表刻意避开「注册」这一类实例词：见类注释。 */
    private static final List<String> EVENT_CUES = List.of("事件", "变更", "上下线", "剔除", "注销", "注册记录");

    private InvestigationCueDetector() {
    }

    /** 问题是否在问生效配置（限流、熔断、超时、采样率等）。 */
    public static boolean mentionsConfig(String question) {
        return mentions(question, CONFIG_CUES);
    }

    /** 问题是否在问注册中心的实例变更经过。 */
    public static boolean mentionsEvent(String question) {
        return mentions(question, EVENT_CUES);
    }

    private static boolean mentions(String question, List<String> cues) {
        String text = question == null ? "" : question.trim().toLowerCase();
        if (text.isEmpty()) {
            return false;
        }
        for (String cue : cues) {
            if (text.contains(cue)) {
                return true;
            }
        }
        return false;
    }
}
