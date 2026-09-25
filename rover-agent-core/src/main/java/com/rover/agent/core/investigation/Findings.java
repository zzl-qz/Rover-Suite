package com.rover.agent.core.investigation;

import com.rover.agent.core.model.Confidence;
import com.rover.agent.core.model.Hypothesis;
import java.util.List;

/**
 * 结论合成结果。
 *
 * @param hypotheses 假设验证明细
 * @param summary    结论摘要
 * @param confidence 结论置信度
 */
public record Findings(List<Hypothesis> hypotheses, String summary, Confidence confidence) { }