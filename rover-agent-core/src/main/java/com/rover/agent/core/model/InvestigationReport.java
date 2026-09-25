package com.rover.agent.core.model;

import java.util.List;

/**
 * 一次调查的结论报告。
 *
 * @param summary     结论摘要
 * @param confidence  结论置信度
 * @param evidence    支撑结论的只读证据
 * @param limitations 本次判断的适用边界
 * @param hypotheses  假设验证明细
 * @param aiAnalysis  模型解读；模型不可用或未读取必要证据时为空
 */
public record InvestigationReport(String summary, Confidence confidence, List<Evidence> evidence,
                                  List<String> limitations, List<Hypothesis> hypotheses, String aiAnalysis) { }