package com.rover.agent.runtime.llm;

import java.util.Optional;
import java.util.function.Function;

/**
 * 调用模型并通过 parser 转换结构化结果；未配置或失败时返回空。
 * 实现统一记录调用结局，解析不符合契约时记为 REJECTED。
 */
@FunctionalInterface
public interface JsonCompletion {

    /**
     * 让模型按提示词输出一段 JSON，并用 {@code parser} 转成结构化结果。
     *
     * @param systemPrompt 系统提示词（角色与输出格式约束）
     * @param userPrompt   用户提示词（问题、可用能力、已有证据）
     * @param parser       契约：把原始输出转成结果；返回空表示输出不合契约，整条丢弃
     * @return 结构化结果；未配置模型、调用失败、没有内容或不合契约时返回空，调用方随之走确定性兜底
     */
    <T> Optional<T> complete(String systemPrompt, String userPrompt, Function<String, Optional<T>> parser);
}
