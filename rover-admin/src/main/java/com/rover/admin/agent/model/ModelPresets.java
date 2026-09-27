package com.rover.admin.agent.model;

import java.util.List;

/**
 * 预设模型服务商：页面下拉与后端共用同一份真值，避免两边各写一套。
 *
 * 只保留 DeepSeek 与智谱 GLM 两家——它们能走原生协议接入，因而能展示模型的深度思考过程；
 * 其余 OpenAI 兼容服务照样可用（地址与模型名都可自由填写），只是拿不到思考内容。
 * 预设是起点而不是白名单：少而准比多而杂更好维护，也让页面上没有需要逐条辨认的选项。
 *
 * 这里只给"服务地址 + 推荐模型名"，密钥一律不由预设携带。
 */
public final class ModelPresets {

    /** 一个预设：展示名、服务地址、推荐模型名。 */
    public record Preset(String label, String baseUrl, String model) {
    }

    /** 主模型预设：服务「AI 解读 / 对话」这类需要推理质量的长调用。 */
    public static final List<Preset> ALL = List.of(
            new Preset("DeepSeek", "https://api.deepseek.com", "deepseek-chat"),
            new Preset("智谱 GLM（思考）", "https://open.bigmodel.cn/api/paas/v4", "glm-4.6"));

    /** 快速模型预设：服务「意图识别 / 目标解析 / 规划」这类廉价结构化调用。 */
    public static final List<Preset> FAST_ALL = List.of(
            new Preset("智谱 GLM-Air（快）", "https://open.bigmodel.cn/api/paas/v4", "glm-4-air"),
            new Preset("智谱 GLM-Flash（快）", "https://open.bigmodel.cn/api/paas/v4", "glm-4-flash"),
            new Preset("DeepSeek-Chat（快）", "https://api.deepseek.com", "deepseek-chat"));

    private ModelPresets() {
    }
}