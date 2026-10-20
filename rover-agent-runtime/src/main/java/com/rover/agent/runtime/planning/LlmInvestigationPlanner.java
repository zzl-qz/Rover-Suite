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
import java.util.Set;
import java.util.Comparator;
import java.util.LinkedHashMap;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rover.agent.core.model.EvidenceQuality;

/**
 * 在规则兜底计划上补充模型建议，能力和目标须通过确定性校验，必查项由规则补齐。
 * 停止条件由规则判断，轮数、调用次数和步数由 PlanningLimits 控制。
 */
public final class LlmInvestigationPlanner implements InvestigationPlanner {

    private static final String SYSTEM_PROMPT = "你是 Rover 运维 Agent 的调查规划模块。"
            + "根据用户问题、可用只读能力与已采集证据，给出下一步要做的只读查询。"
            + "只能从给出的能力名里选择，不要编造能力、不要输出命令、URL、SQL 或任何写操作。"
            + "只输出JSON，每轮最多3个新候选、6个步骤。步骤只写能力名。"
            + "先检验已有假设的证据缺口，必要时提出新的可验证方向，不能重复列出已知假设。"
            + "候选必须说明需要什么直接证据；信息不足可以返回空候选，不能把样本、相关性或缺失数据当根因证明。"
            + UntrustedText.contract();

    private static final int MAX_LINE_LENGTH = 160;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> PLANNING_FACTS = Set.of("samplePopulations", "finalRequests", "final5xx",
            "final5xxRate", "responseOrigins", "failurePhases", "incompleteResponses", "samplingMode",
            "coverageFromMillis", "coverageToMillis", "dimensions", "dimensionsMayBeTruncated", "versions",
            "events", "causalityConfirmed", "truncated");

    private final RuleBasedPlanner rules;
    private final JsonCompletion completion;
    private final CapabilityRegistry registry;
    private final int evidenceContextChars;

    public LlmInvestigationPlanner(RuleBasedPlanner rules, JsonCompletion completion, CapabilityRegistry registry) {
        this(rules, completion, registry, 24000);
    }

    public LlmInvestigationPlanner(RuleBasedPlanner rules, JsonCompletion completion, CapabilityRegistry registry,
                                  int evidenceContextChars) {
        if (evidenceContextChars < 4000 || evidenceContextChars > 100000) {
            throw new IllegalArgumentException("规划证据上下文字符预算必须在 4000～100000 之间");
        }
        this.rules = rules == null ? new RuleBasedPlanner(registry) : rules;
        this.completion = completion;
        this.registry = registry == null ? CapabilityRegistry.standard() : registry;
        this.evidenceContextChars = evidenceContextChars;
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
        for (String name : ModelJson.strings(node, "steps")) {
            AgentCapability capability = capability(name);
            if (capability != null && registry.selectable(capability) && !contains(steps, capability)) {
                steps.add(PlannedStep.optional(capability, "模型选择：" + capability, PlanValidator.requestTarget(request)));
            }
        }
        // 兼容已保存的旧对象格式；新请求只需输出能力名，减少生成耗时。
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
            if (step.required()) {
                int index = java.util.stream.IntStream.range(0, steps.size())
                        .filter(i -> steps.get(i).capability() == step.capability()).findFirst().orElse(-1);
                if (index < 0) { steps.add(step); }
                else {
                    PlannedStep chosen = steps.get(index);
                    steps.set(index, new PlannedStep(chosen.capability(), chosen.reason(), chosen.target(), true));
                }
            }
        }
        String goal = ModelJson.text(node, "goal");
        List<Hypothesis> assessments = new ArrayList<>(request.assessments());
        List<String> hypotheses = new ArrayList<>();
        // 兼容旧模型字符串计划；新候选只作为待验证方向，模型不能覆盖规则状态或编造证据引用。
        hypotheses.addAll(ModelJson.strings(node, "hypotheses"));
        int addedCandidates = 0;
        for (JsonNode item : ModelJson.objects(node, "candidates")) {
            if (addedCandidates >= 3) { break; }
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
            addedCandidates++;
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
                .append("\n可用只读能力：");
        for (CapabilityDescriptor descriptor : registry.selectable()) {
            if (descriptor.supports(PlanValidator.requestTarget(request).type())) {
                prompt.append("\n").append(descriptor.id()).append(":").append(descriptor.name());
            }
        }
        if (request.evidence().isEmpty()) {
            prompt.append("\n已采集证据：暂无（这是第一轮规划）");
        } else {
            // 证据摘要来自网关与注册中心的事实（可能含外部写入的名称、错误文本），按不可信数据围起来。
            prompt.append("\n已采集证据：\n").append(UntrustedText.block("已采集证据", evidenceContext(request)));
        }
        if (!request.limitations().isEmpty()) {
            prompt.append("\n已知判断边界：").append(cut(String.join("；", request.limitations())));
        }
        for (Hypothesis hypothesis : request.assessments()) {
            prompt.append(UntrustedText.block("假设 " + hypothesis.id(), hypothesis.statement() + " 状态=" + hypothesis.status()
                    + " 支持=" + hypothesis.supportingEvidenceIds() + " 反证=" + hypothesis.counterEvidenceIds()
                    + " 缺口=" + cut(String.join("；", hypothesis.missingEvidence())) + " 下一步=" + hypothesis.nextCapabilities()));
        }
        prompt.append("\n不要把工具执行过当成验证成功；新方向只列为候选，状态由运行时判断。")
                .append("\n输出{\"steps\":[\"能力名\"],\"candidates\":[]}。确有新方向时，candidates最多3项，字段为statement、missingEvidence、nextCapabilities。");
        return prompt.toString();
    }

    private String evidenceContext(PlanningRequest request) {
        Set<String> referenced = new java.util.HashSet<>();
        request.assessments().forEach(h -> { referenced.addAll(h.supportingEvidenceIds()); referenced.addAll(h.counterEvidenceIds()); });
        // 优先保留已有假设的引用与可用观测；当前快照不能挤掉调查窗口内的事实。
        List<Evidence> ordered = request.evidence().stream().sorted(Comparator
                .comparingInt((Evidence e) -> referenced.contains(e.evidenceId()) ? 0
                        : e.envelope().qualityStatus() == EvidenceQuality.AVAILABLE || e.envelope().qualityStatus() == EvidenceQuality.PARTIAL ? 1 : 2)
                .thenComparing(Comparator.comparingLong(Evidence::observedAtMillis).reversed())).toList();
        StringBuilder out = new StringBuilder();
        int omitted = 0;
        for (Evidence e : ordered) {
            String metadata = "- " + e.evidenceId() + " [" + e.type() + "] " + e.envelope().qualityStatus()
                    + " 样本=" + e.envelope().sampleCount() + " 调查窗口=" + e.envelope().requestedRange().describe();
            String line = metadata + "\n摘要=" + limited(e.summary(), 1200) + "\n" + structuredFacts(e) + "\n";
            if (out.length() + line.length() <= evidenceContextChars - 100) { out.append(line); }
            else { omitted++; }
        }
        if (omitted > 0) { out.append("…证据上下文达到字符预算，已省略 ").append(omitted).append(" 项；省略不表示无异常或没有变更。原始证据仍保留。\n"); }
        return out.toString();
    }

    private static String structuredFacts(Evidence evidence) {
        var facts = new LinkedHashMap<String, Object>();
        evidence.envelope().facts().forEach((key, value) -> { if (PLANNING_FACTS.contains(key)) { facts.put(key, value); } });
        if (facts.isEmpty()) { return ""; }
        try { return "结构化事实（来源观测，非因果证明）=" + limited(JSON.writeValueAsString(facts), 6000); }
        catch (Exception invalid) { return "结构化事实无法序列化，不能据此确认或排除病因。"; }
    }

    private static String limited(String text, int max) {
        String value = text == null ? "" : text.trim();
        return value.length() > max ? value.substring(0, max) + "…（已截断，不能推断被省略部分）" : value;
    }

    private static String cut(String text) {
        String value = text == null ? "" : text.trim();
        return value.length() > MAX_LINE_LENGTH ? value.substring(0, MAX_LINE_LENGTH) + "…" : value;
    }
}
