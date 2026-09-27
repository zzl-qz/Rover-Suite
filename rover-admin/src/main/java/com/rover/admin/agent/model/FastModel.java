package com.rover.admin.agent.model;

/**
 * 快速模型配置：承接意图识别、目标解析、调查规划这类「结构化小任务」。
 *
 * <p>这类调用有几个共同点：输出取值受限（要么命中候选清单、要么 UNKNOWN）、失败能被规则兜底、
 * 重试成本低。它们不需要深度思考模型的推理质量，用轻量便宜的快模型即可——把思考模型省下来
 * 只服务「AI 解读」这类真正需要推理的长调用。
 *
 * <p>未配置（{@code model} 为空）时，网关自动回退到主模型 + 显式禁用思考，保证向后兼容。
 */
public record FastModel(String baseUrl, String apiKey, String model) {

    /** 是否「配置了」快速模型：地址与模型名齐全（本地无鉴权模型可无密钥）。 */
    public boolean configured() {
        return notBlank(baseUrl) && notBlank(model);
    }

    /** 未配置快速模型的起点。 */
    public static FastModel none() {
        return new FastModel("", "", "");
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
