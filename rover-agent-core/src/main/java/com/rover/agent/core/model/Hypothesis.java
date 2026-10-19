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
public record Hypothesis(String id, String statement, Verdict status, String detail, List<String> sources,
                         List<String> supportingEvidenceIds, List<String> counterEvidenceIds,
                         List<String> missingEvidence,
                         List<com.rover.agent.core.capability.AgentCapability> nextCapabilities) {
    public Hypothesis {
        status = status == null ? Verdict.UNKNOWN : status;
        sources = sources == null ? List.of() : List.copyOf(sources);
        supportingEvidenceIds = supportingEvidenceIds == null ? List.of() : List.copyOf(supportingEvidenceIds);
        counterEvidenceIds = counterEvidenceIds == null ? List.of() : List.copyOf(counterEvidenceIds);
        missingEvidence = missingEvidence == null ? List.of() : List.copyOf(missingEvidence);
        nextCapabilities = nextCapabilities == null ? List.of() : List.copyOf(nextCapabilities);
    }

    /** 旧报告没有证据关联，读取时保持为空，不能补造引用。 */
    public Hypothesis(String id, String statement, Verdict status, String detail, List<String> sources) {
        this(id, statement, status, detail, sources, List.of(), List.of(), List.of(), List.of());
    }
}
