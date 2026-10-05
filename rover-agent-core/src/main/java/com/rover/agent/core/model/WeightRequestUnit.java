package com.rover.agent.core.model;

/**
 * 权重变更数值的单位：RAW_WEIGHT 为相对权重，TRAFFIC_PERCENT 为流量百分比。
 * 单位必须显式指定；缺失或不确定时要求澄清，百分比由代码换算。
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

    /** 解析单位文本；UNSURE 或无法识别时返回空，要求澄清。 */
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
