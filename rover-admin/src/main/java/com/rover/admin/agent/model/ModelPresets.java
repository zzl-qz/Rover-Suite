package com.rover.admin.agent.model;

import java.util.List;

/**
 * 预设模型服务商：页面下拉与后端共用同一份真值，避免两边各写一套。
 *
 * 这里只给"服务地址 + 推荐模型名"的起点，用户始终可以改成任意自定义值；
 * 密钥一律不由预设携带。
 */
public final class ModelPresets {

    /** 一个预设：展示名、OpenAI 兼容服务地址、推荐模型名。 */
    public record Preset(String label, String baseUrl, String model) {
    }

    public static final List<Preset> ALL = List.of(
            new Preset("OpenAI", "https://api.openai.com", "gpt-4o-mini"),
            new Preset("DeepSeek", "https://api.deepseek.com", "deepseek-chat"),
            new Preset("阿里云百炼（兼容模式）", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus"),
            new Preset("智谱开放平台", "https://open.bigmodel.cn/api/paas/v4", "glm-4-air"),
            new Preset("本地 Ollama", "http://127.0.0.1:11434/v1", "qwen2.5:7b"),
            new Preset("本地 vLLM", "http://127.0.0.1:8000/v1", "Qwen2.5-7B-Instruct"));

    private ModelPresets() {
    }
}