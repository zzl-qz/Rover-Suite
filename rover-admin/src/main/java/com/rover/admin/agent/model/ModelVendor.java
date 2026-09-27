package com.rover.admin.agent.model;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 模型厂商：用户只选「厂商 + 填 key」，服务地址、主模型、快速模型都由后台映射，
 * 不再要求用户理解「glm-4.6 和 glm-4-air 的区别」。
 *
 * <p>映射规则是运维/开发者的决策，不是终端用户的决策：哪家该用思考模型做解读、
 * 哪家该用轻量模型做廉价调用，都固化在这里，随版本一起维护。
 *
 * <p>{@link #CUSTOM} 是给本地部署（Ollama / vLLM）与代理网关留的口子：这些场景没有
 * 「厂商」概念，地址与模型名必须手填。其余厂商的 baseUrl / 模型名是只读的默认值。
 */
public enum ModelVendor {

    /** 深度求索：deepseek-chat 本身不思考，无需单独快速模型，主/快共用同一模型。 */
    DEEPSEEK("deepseek", "DeepSeek", "https://api.deepseek.com", "deepseek-chat", ""),

    /** 智谱：主模型用思考模型（解读/对话），快速模型用轻量模型（意图/目标/规划）。 */
    ZHIPU("zhipu", "智谱 GLM", "https://open.bigmodel.cn/api/paas/v4", "glm-4.6", "glm-4-air"),

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
