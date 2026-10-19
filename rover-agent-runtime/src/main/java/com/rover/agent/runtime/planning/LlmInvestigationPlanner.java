package com.rover.agent.runtime.planning;

import com.fasterxml.jackson.databind.JsonNode;
import com.rover.agent.core.capability.AgentCapability;
import com.rover.agent.core.capability.CapabilityDescriptor;
import com.rover.agent.core.capability.CapabilityRegistry;
import com.rover.agent.core.capability.UntrustedText;
import com.rover.agent.core.model.Evidence;
import com.rover.agent.core.model.Hypothesis;
import com.rover.agent.core.model.Verdict;
import com.rover.agent.core.planning.InvestigationPlan;
import com.rover.agent.core.planning.InvestigationPlanner;
import com.rover.agent.core.planning.PlanProgress;
import com.rover.agent.core.planning.PlanValidator;
import com.rover.agent.core.planning.PlannedStep;
import com.rover.agent.core.planning.PlanningDecision;
import com.rover.agent.core.planning.PlanningRequest;
import com.rover.agent.core.planning.RuleBasedPlanner;
import com.rover.agent.runtime.llm.JsonCompletion;
import com.rover.agent.runtime.llm.ModelJson;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 在规则兜底计划上补充模型建议，能力和目标须通过确定性校验，必查项由规则补齐。
 * 停止条件由规则判断，轮数、调用次数和步数由 PlanningLimits 控制。
 */
public final class LlmInvestigationPlanner implements InvestigationPlanner {

    private static final String SYSTEM_PROMPT = "你是 Rover 运维 Agent 的调查规划模块。"
            + "根据用户问题、可用只读能力与已采集证据，给出下一步要做的只读查询。"
            + "只能从给出的能力名里选择，不要编造能力、不要输出命令、URL、SQL 或任何写操作。"
            + "只输出一个 JSON 对象，不要输出解释文字，也不要用代码块包裹。"
            + UntrustedText.contract();

    private static final int MAX_EVIDENCE_LINES = 8;
    private static final int MAX_LINE_LENGTH = 160;

    private final RuleBasedPlanner rules;
    private final JsonCompletion completion;
    private final CapabilityRegistry registry;

    public LlmInvestigationPlanner(RuleBasedPlanner rules, JsonCompletion completion, CapabilityRegistry registry) {
        this.rules = rules == null ? new RuleBasedPlanner(registry) : rules;
        this.completion = completion;
        this.registry = registry == null ? CapabilityRegistry.standard() : registry;
    }

    @Override
    public InvestigationPlan plan(PlanningRequest request) {
        InvestigationPlan base = rules.plan(request);
        if (completion == null) {
            return base;
        }
        return proposal(request, base).orElse(base);
    }

    @Override
    public PlanningDecision evaluate(PlanningRequest request, PlanProgress progress) {
        return rules.evaluate(request, progress);
    }

    /** 请模型给出计划建议；返回空表示「没有可用建议」，调用方沿用确定性计划。 */
    private Optional<InvestigationPlan> proposal(PlanningRequest request, InvestigationPlan base) {
        String user = userPrompt(request);
        // 契约：必须是合法 JSON 对象；能力名越界由 merge 丢弃（那是内容级校验，不影响整条采纳）。
        return completion.complete(SYSTEM_PROMPT, user,
                raw -> ModelJson.object(raw).map(node -> merge(node, request, base)));
    }

    /**
     * 合并模型建议与确定性计划。
     *
     * 模型步骤按模型给出的顺序排在前面；确定性计划里的必查项如果被模型漏掉，追加到末尾——
     * 「漏查关键事实」不能让模型一句话决定。
     */
    private InvestigationPlan merge(JsonNode node, PlanningRequest request, InvestigationPlan base) {
        List<PlannedStep> steps = new ArrayList<>();
        for (JsonNode item : ModelJson.objects(node, "steps")) {
            AgentCapability capability = capability(ModelJson.text(item, "capability"));
            if (capability == null || !registry.selectable(capability) || contains(steps, capability)) {
                continue;
            }
            String reason = ModelJson.text(item, "reason");
            steps.add(PlannedStep.optional(capability,
                    reason.isBlank() ? "模型建议：读取 " + capability : reason, PlanValidator.requestTarget(request)));
        }
        for (PlannedStep step : base.steps()) {
            if (step.required() && !contains(steps, step.capability())) {
                steps.add(step);
            }
        }
        String goal = ModelJson.text(node, "goal");
        List<Hypothesis> assessments = new ArrayList<>(request.assessments());
        List<String> hypotheses = new ArrayList<>();
        // 兼容旧模型字符串计划；新候选只作为待验证方向，模型不能覆盖规则状态或编造证据引用。
        hypotheses.addAll(ModelJson.strings(node, "hypotheses"));
        for (JsonNode item : ModelJson.objects(node, "candidates")) {
            String statement = cut(ModelJson.text(item, "statement"));
            if (statement.isBlank() || assessments.stream().anyMatch(h -> statement.equals(h.statement()))
                    || assessments.stream().filter(h -> h.id().startsWith("M")).count() >= 6) { continue; }
            List<AgentCapability> next = new ArrayList<>();
            for (String name : ModelJson.strings(item, "nextCapabilities")) {
                AgentCapability capability = capability(name);
                if (capability != null && registry.selectable(capability) && !next.contains(capability)) {
                    next.add(capability);
                    if (!contains(steps, capability)) { steps.add(PlannedStep.optional(capability, "验证候选：" + statement, PlanValidator.requestTarget(request))); }
                }
            }
            String id = "M" + (assessments.stream().filter(h -> h.id().startsWith("M")).count() + 1);
            List<String> missing = ModelJson.strings(item, "missingEvidence").stream().limit(4).map(LlmInvestigationPlanner::cut).toList();
            assessments.add(new Hypothesis(id, statement, Verdict.UNKNOWN, "模型提出的候选，尚无确定性证据确认",
                    List.of(), List.of(), List.of(), missing.isEmpty() ? List.of("需要可复核的直接证据") : missing, next));
            hypotheses.add(statement);
        }
        return new InvestigationPlan(goal.isBlank() ? base.goal() : goal,
                hypotheses.isEmpty() ? base.hypotheses() : hypotheses, steps, assessments, base.progress());
    }

    private static boolean contains(List<PlannedStep> steps, AgentCapability capability) {
        return steps.stream().anyMatch(step -> step.capability() == capability);
    }

    private static AgentCapability capability(String name) {
        try {
            return name.isBlank() ? null : AgentCapability.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private String userPrompt(PlanningRequest request) {
        StringBuilder prompt = new StringBuilder(UntrustedText.block("用户问题", request.question()))
                .append("目标对象：").append(request.target().type()).append(" ").append(request.target().value())
                .append("\n请求路径：").append(request.path().isBlank() ? "（未确定）" : request.path())
                .append("\n调查时间范围：").append(request.timeRange().describe())
                .append("\n可用只读能力（只能从这些名字里选）：");
        for (CapabilityDescriptor descriptor : registry.selectable()) {
            prompt.append("\n- ").append(descriptor.id()).append("（").append(descriptor.name()).append("）：")
                    .append(descriptor.description()).append("；适用目标：")
                    .append(descriptor.supportedTargetTypes().isEmpty() ? "全局"
                            : String.join("/", descriptor.supportedTargetTypes().stream().map(Enum::name).toList()));
        }
        if (request.evidence().isEmpty()) {
            prompt.append("\n已采集证据：暂无（这是第一轮规划）");
        } else {
            // 证据摘要来自网关与注册中心的事实（可能含外部写入的名称、错误文本），按不可信数据围起来。
            StringBuilder evidenceLines = new StringBuilder();
            int count = 0;
            for (Evidence evidence : request.evidence()) {
                if (count++ >= MAX_EVIDENCE_LINES) {
                    evidenceLines.append("- …（其余证据略）\n");
                    break;
                }
                evidenceLines.append("- ").append(evidence.evidenceId()).append(" [").append(evidence.type()).append("] ")
                        .append(cut(evidence.modelText())).append('\n');
            }
            prompt.append("\n已采集证据：\n").append(UntrustedText.block("已采集证据", evidenceLines.toString()));
        }
        if (!request.limitations().isEmpty()) {
            prompt.append("\n已知判断边界：").append(cut(String.join("；", request.limitations())));
        }
        for (Hypothesis hypothesis : request.assessments()) {
            prompt.append(UntrustedText.block("假设 " + hypothesis.id(), hypothesis.statement() + " 状态=" + hypothesis.status()
                    + " 支持证据=" + hypothesis.supportingEvidenceIds() + " 反证=" + hypothesis.counterEvidenceIds()
                    + " 缺口=" + hypothesis.missingEvidence() + " 下一步=" + hypothesis.nextCapabilities()));
        }
        prompt.append("\n不要把工具执行过当成验证成功；新方向只列为候选，状态由运行时判断。")
                .append("\n输出 JSON：{\"goal\":\"本次要回答的问题\",\"candidates\":[{\"statement\":\"候选原因\",\"missingEvidence\":[\"尚缺证据\"],\"nextCapabilities\":[\"能力名\"]}],")
                .append("\"steps\":[{\"capability\":\"能力名\",\"reason\":\"选择依据\"}]}");
        return prompt.toString();
    }

    private static String cut(String text) {
        String value = text == null ? "" : text.trim();
        return value.length() > MAX_LINE_LENGTH ? value.substring(0, MAX_LINE_LENGTH) + "…" : value;
    }
}
