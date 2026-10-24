package com.rover.admin.agent.model;

/**
 * 目标解析和事实编号选择使用的快速模型配置；调查规划独立使用主模型。
 * 未配置时回退到主模型，并禁用深度思考。
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
