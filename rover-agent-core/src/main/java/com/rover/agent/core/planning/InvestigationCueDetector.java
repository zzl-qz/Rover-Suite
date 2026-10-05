package com.rover.agent.core.planning;

import java.util.List;

/**
 * 识别配置与事件查询提示，供 {@link RuleBasedPlanner} 追加可选步骤。
 * 两个方向独立判断，仅匹配明确询问相关事实的词语。
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
