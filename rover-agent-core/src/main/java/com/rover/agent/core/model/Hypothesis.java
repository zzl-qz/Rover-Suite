package com.rover.agent.core.model;

import java.util.List;

/**
 * 一条待验证的调查假设：只由已采集的证据确认或排除。
 *
 * @param id        假设编号，例如 {@code H1}
 * @param statement 候选故障原因
 * @param status    验证结论
 * @param detail    结论依据
 * @param sources   支撑该结论的证据来源
 */
public record Hypothesis(String id, String statement, Verdict status, String detail, List<String> sources) { }