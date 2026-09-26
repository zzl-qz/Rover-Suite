package com.rover.agent.runtime.llm;

import java.util.Optional;

/**
 * 一次「要求模型输出 JSON」的最小调用口。
 *
 * 决策逻辑（意图理解、调查规划）依赖这个函数口而不是 Spring AI：测试里用 lambda 返回一段 JSON
 * 就能覆盖「模型给出建议」的全部路径，不需要真实模型，也不需要打桩工具调用循环。
 * 实现必须保证「未配置模型」时返回 {@link Optional#empty()}，绝不伪造模型输出。
 */
@FunctionalInterface
public interface JsonCompletion {

    /**
     * 让模型按提示词输出一段 JSON。
     *
     * @param systemPrompt 系统提示词（角色与输出格式约束）
     * @param userPrompt   用户提示词（问题、可用能力、已有证据）
     * @return 模型原始输出；未配置模型、调用失败或没有内容时返回空
     */
    Optional<String> complete(String systemPrompt, String userPrompt);
}