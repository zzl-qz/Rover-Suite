package com.rover.agent.runtime.graph;

import com.rover.agent.core.investigation.Findings;
import com.rover.agent.core.model.Evidence;
import java.util.List;

/**
 * 一次只读调查的产出：结论合成结果，外加支撑它的证据与判断边界。
 *
 * @param findings    假设验证结论与置信度
 * @param evidence    已采集的只读证据
 * @param limitations 本次判断的适用边界
 */
public record InvestigationOutcome(Findings findings, List<Evidence> evidence, List<String> limitations) { }