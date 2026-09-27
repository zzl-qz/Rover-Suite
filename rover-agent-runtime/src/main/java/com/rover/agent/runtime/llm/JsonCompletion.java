package com.rover.agent.runtime.llm;

import java.util.Optional;
import java.util.function.Function;

/**
 * 一次「要求模型输出 JSON 并转成结构化结果」的最小调用口。
 *
 * 决策逻辑（意图理解、调查规划）依赖这个函数口而不是 Spring AI：测试里用 lambda 返回一段 JSON
 * 就能覆盖「模型给出建议」的全部路径，不需要真实模型，也不需要打桩工具调用循环。
 * 实现必须保证「未配置模型」时返回 {@link Optional#empty()}，绝不伪造模型输出。
 *
 * 解析交给调用方传进来的 {@code parser}，而「这次调用算成功还是失败、失败在哪一环」由实现统一记录：
 * 文本拿回来但解析不出来，说明输出不合契约（越界取值、缺字段），实现会把它记成
 * {@link com.rover.agent.runtime.metrics.ModelCallOutcome#REJECTED}，
 * 与「没配模型 / 超时 / 报错 / 返回空」分开计数。这样调用方只写契约，不必各自重复埋点，
 * 也不会出现某个场景漏记导致命中率对不上账。
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
