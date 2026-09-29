package com.rover.agent.core.model;

/**
 * 一次权重变更请求里「那个数字」的单位。
 *
 * <p>灰度权重在这套系统里有两个完全不同的读法，混用会直接导致事故级别的结果偏差：
 *
 * <ul>
 *   <li><b>{@link #RAW_WEIGHT}</b>：网关路由表里的权重值本身（0~10000 的相对值）。
 *       「把 v2 的权重调到 20」说的是它。同一条路由上各目标的权重是相对关系，
 *       所以 20 究竟代表多少流量，取决于同路由上其他目标的权重。</li>
 *   <li><b>{@link #TRAFFIC_PERCENT}</b>：这个版本最终要承接的流量占比（0~100）。
 *       「把 v2 的流量调到 20%」说的是它。占比到权重值的换算依赖整条路由的权重分布，
 *       因此必须由代码算（见 {@code AgentActionService}），不能让模型去猜。</li>
 * </ul>
 *
 * <p>为什么单位必须显式、而不是从数字大小推断：用户说「放量到 20」时，
 * 20 既可能是权重值也可能是 20% 流量——在当前权重是 5 / 95 这类小数量纲下，
 * 两种读法算出来的结果能差两个数量级（20 与 2000）。
 * 让模型自己挑一个，等于把一次真实变更押在语气判断上；
 * 因此这个枚举是提议的必填项，缺失就是「必须先向用户澄清」，而不是「替他选一个」。
 */
public enum WeightRequestUnit {

    /** 网关路由表里的权重值（0~10000）。 */
    RAW_WEIGHT("权重值"),

    /** 目标版本要承接的流量占比（0~100，不含 100）。 */
    TRAFFIC_PERCENT("流量占比");

    private final String label;

    WeightRequestUnit(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /**
     * 解析外部传入的单位文本；无法识别时返回空，由调用方按「需要澄清」处理。
     *
     * <p>刻意接受 {@code UNSURE} 这个特殊值：模型在拿不准时有一个明确的「我不知道」可以说，
     * 比被迫在两个选项里猜一个安全得多。
     */
    public static WeightRequestUnit parse(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toUpperCase(java.util.Locale.ROOT)
                .replace('-', '_').replace(' ', '_');
        return switch (normalized) {
            case "RAW_WEIGHT", "WEIGHT", "RAW" -> RAW_WEIGHT;
            case "TRAFFIC_PERCENT", "PERCENT", "TRAFFIC", "PERCENTAGE" -> TRAFFIC_PERCENT;
            default -> null;
        };
    }

    /** 是否模型显式表达了「不确定」。 */
    public static boolean isUnsure(String value) {
        if (value == null) {
            return true;
        }
        String normalized = value.trim().toUpperCase(java.util.Locale.ROOT);
        return normalized.isBlank() || normalized.equals("UNSURE") || normalized.equals("UNKNOWN")
                || normalized.equals("AMBIGUOUS") || normalized.equals("CLARIFICATION_REQUIRED");
    }
}
