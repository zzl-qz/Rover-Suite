package com.rover.admin.agent.model;

import java.util.List;

/**
 * 页面与后端共用的模型服务商预设，仅提供地址与模型名。
 * 预设包含 DeepSeek 和智谱；允许自定义其他兼容服务，不包含密钥。
 */
public final class ModelPresets {

    /** 一个预设：展示名、服务地址、推荐模型名。 */
    public record Preset(String label, String baseUrl, String model) {
    }

    /** 主模型预设：服务「AI 解读 / 对话」这类需要推理质量的长调用。 */
    public static final List<Preset> ALL = List.of(
            new Preset("DeepSeek", "https://api.deepseek.com", "deepseek-chat"),
            new Preset("智谱 GLM（思考）", "https://open.bigmodel.cn/api/paas/v4", "glm-4.6"));

    /** 快速模型预设：服务「目标解析 / 调查规划」这类廉价结构化调用。 */
    public static final List<Preset> FAST_ALL = List.of(
            new Preset("智谱 GLM-Flash（快，默认）", "https://open.bigmodel.cn/api/paas/v4", "glm-4-flash"),
            new Preset("智谱 GLM-Air（更快）", "https://open.bigmodel.cn/api/paas/v4", "glm-4-air"),
            new Preset("DeepSeek-Chat（快）", "https://api.deepseek.com", "deepseek-chat"));

    private ModelPresets() {
    }
}