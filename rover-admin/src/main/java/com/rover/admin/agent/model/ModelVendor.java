package com.rover.admin.agent.model;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 模型厂商及默认地址、主模型和快速模型映射。
 * CUSTOM 支持自定义服务地址与模型名。
 */
public enum ModelVendor {

    /** 深度求索：deepseek-chat 本身不思考，无需单独快速模型，主/快共用同一模型。 */
    DEEPSEEK("deepseek", "DeepSeek", "https://api.deepseek.com", "deepseek-chat", ""),

    /** 智谱：主模型用于解读和对话，快速模型用于目标解析和规划。 */
    ZHIPU("zhipu", "智谱 GLM", "https://open.bigmodel.cn/api/paas/v4", "glm-4.6", "glm-4-flash"),

    /** 自定义 / 本地部署：地址与模型名由用户手填。 */
    CUSTOM("custom", "自定义 / 本地", "", "", "");

    private final String code;
    private final String label;
    private final String baseUrl;
    private final String mainModel;
    private final String fastModel;

    ModelVendor(String code, String label, String baseUrl, String mainModel, String fastModel) {
        this.code = code;
        this.label = label;
        this.baseUrl = baseUrl;
        this.mainModel = mainModel;
        this.fastModel = fastModel;
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
    }

    public String baseUrl() {
        return baseUrl;
    }

    public String mainModel() {
        return mainModel;
    }

    public String fastModel() {
        return fastModel;
    }

    /** 按 code 解析；未知 code 一律落到 {@link #CUSTOM}（宁可让用户手填，不猜厂商）。 */
    public static ModelVendor of(String code) {
        if (code == null) {
            return CUSTOM;
        }
        for (ModelVendor vendor : values()) {
            if (vendor.code.equalsIgnoreCase(code.trim())) {
                return vendor;
            }
        }
        return CUSTOM;
    }

    /** 从落地地址反推厂商，供页面展示当前归属；识别不了算 {@link #CUSTOM}。 */
    public static ModelVendor infer(String baseUrl) {
        String host = host(baseUrl).toLowerCase(Locale.ROOT);
        if (host.contains("bigmodel.cn") || host.contains("zhipu")) {
            return ZHIPU;
        }
        if (host.contains("deepseek.com")) {
            return DEEPSEEK;
        }
        return CUSTOM;
    }

    /** 供页面渲染的厂商清单（不含密钥）。 */
    public static List<ModelVendor> list() {
        return Arrays.asList(values());
    }

    private static String host(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "";
        }
        try {
            java.net.URI uri = java.net.URI.create(baseUrl);
            return uri.getHost() == null ? baseUrl : uri.getHost();
        } catch (IllegalArgumentException ex) {
            return baseUrl;
        }
    }
}
