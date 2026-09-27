package com.rover.agent.core.intent;

import com.rover.agent.core.model.ActionType;
import com.rover.agent.core.model.AgentIntent;
import com.rover.agent.core.model.Confidence;
import com.rover.agent.core.model.IntentDecision;
import com.rover.agent.core.model.IntentTopic;
import com.rover.agent.core.model.TimeRange;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 意图判定入口：规则分类是底线，模型辅助只补「规则说不清」的场合。
 *
 * 两者的分工是刻意的：
 * <ul>
 *   <li>规则命中明确（处置请求、能力咨询、定时巡检）时直接用规则结果，不把确定性让给模型；</li>
 *   <li>规则判断为中等/低置信度时，先问模型；模型给出可用判断就采用，但目标线索与时间范围
 *       这些「从文本直接读到的既有事实」以规则抽取为准（模型不重复抽取，也不会把它们抹掉）；</li>
 *   <li>模型不可用、返回空或判断不可用（低置信度、要求澄清）时，仍然回到规则结果。</li>
 * </ul>
 *
 * 因此「无模型环境下意图仍然可判」不是降级，而是常态。
 *
 * <p>每次判定都打一条 INFO 日志，用标签标明结论是哪一层产出的，便于在控制台核对
 * 「这次到底走没走模型」：{@code 意图识别[规则直接]} 表示规则高置信度命中、根本没调模型；
 * {@code 意图识别[模型]} 表示采用了模型判断；{@code 意图识别[规则兜底]} 表示问过模型但退回规则。
 */
public final class IntentService {

    private static final Logger log = LoggerFactory.getLogger(IntentService.class);

    private static final int MAX_CONTEXT_SUMMARY_LENGTH = 600;

    /** 日志里的问题预览长度：只用于把日志和刚提的问题对上号，不做全文记录。 */
    private static final int MAX_QUESTION_PREVIEW_LENGTH = 40;

    private final IntentInterpreter interpreter;
    private final IntentClassifier classifier;

    public IntentService(IntentInterpreter interpreter, IntentClassifier classifier) {
        this.interpreter = interpreter == null ? IntentInterpreter.none() : interpreter;
        this.classifier = classifier == null ? new IntentClassifier() : classifier;
    }

    /** 未接入模型时的意图判定：只用规则。 */
    public IntentService() {
        this(IntentInterpreter.none(), new IntentClassifier());
    }

    /** 判定一条用户消息的意图；结果永远非空。 */
    public IntentDecision decide(String question, String contextSummary) {
        String asked = question == null ? "" : question.trim();
        IntentDecision byRules = classifier.classify(asked);
        if (byRules.confidence() == Confidence.HIGH && byRules.intent() != AgentIntent.UNKNOWN) {
            log.info("意图识别[规则直接] 规则高置信度命中，未调用模型：问题=\"{}\" 意图={} 置信度={} 依据={}",
                    preview(asked), byRules.intent(), byRules.confidence(), byRules.reason());
            return byRules;
        }
        Optional<IntentDecision> byModel = interpreter.interpret(asked, trim(contextSummary));
        if (byModel.isEmpty()) {
            log.info("意图识别[规则兜底] 模型未给出结果（未配置模型、调用失败或输出越界）：问题=\"{}\" 规则结果={} 置信度={} 依据={}",
                    preview(asked), byRules.intent(), byRules.confidence(), byRules.reason());
            return byRules;
        }
        IntentDecision candidate = byModel.get();
        if (!usable(candidate)) {
            log.info("意图识别[规则兜底] 模型结果不可用（要求澄清或未给出确定意图）：问题=\"{}\" 模型结果={} 置信度={} 要求澄清={} 规则结果={}",
                    preview(asked), candidate.intent(), candidate.confidence(), candidate.needsClarification(),
                    byRules.intent());
            return byRules;
        }
        IntentDecision merged = merge(candidate, byRules);
        log.info("意图识别[模型] 采用模型判断{}：问题=\"{}\" 模型意图={} 最终意图={} 置信度={} 目标线索=\"{}\" 依据={}",
                merged.intent() == candidate.intent() ? "" : "（被规则修正为处置请求，文本中存在明确处置动作）",
                preview(asked), candidate.intent(), merged.intent(), merged.confidence(), merged.targetHint(),
                merged.reason());
        return merged;
    }

    /** 日志里的问题预览：压平换行并截断，避免一条日志被多行内容撑开。 */
    private static String preview(String question) {
        String flat = question.replaceAll("\\s+", " ").trim();
        if (flat.length() <= MAX_QUESTION_PREVIEW_LENGTH) {
            return flat;
        }
        return flat.substring(0, MAX_QUESTION_PREVIEW_LENGTH) + "…";
    }

    /** 模型判断是否可用：必须给出确定意图，且不要求澄清（澄清一律回退到规则与编排层的兜底）。 */
    private static boolean usable(IntentDecision candidate) {
        if (candidate == null || candidate.needsClarification() || candidate.clarification() != null) {
            return false;
        }
        return candidate.intent() != AgentIntent.UNKNOWN;
    }

    /**
     * 合并模型判断与规则抽取的既有事实：意图取模型的，目标线索与时间范围取规则读到的。
     *
     * 模型只负责「想干什么」这一层理解；把对象线索交给模型重新抽取，等于让它有机会改变调查对象。
     */
    private static IntentDecision merge(IntentDecision candidate, IntentDecision byRules) {
        String hint = candidate.targetHint().isBlank() ? byRules.targetHint() : candidate.targetHint();
        TimeRange range = candidate.timeRange().isUnspecified() ? byRules.timeRange() : candidate.timeRange();
        IntentTopic topic = candidate.topic() == IntentTopic.NONE ? byRules.topic() : candidate.topic();
        ActionType action = candidate.requestedAction() == ActionType.UNKNOWN
                ? byRules.requestedAction() : candidate.requestedAction();
        IntentDecision merged = new IntentDecision(candidate.intent(), candidate.confidence(), topic, hint, range,
                action, candidate.reason(), candidate.needsClarification(), candidate.clarification());
        if (merged.requestedAction() != ActionType.UNKNOWN && merged.intent() != AgentIntent.ACTION_REQUEST) {
            // 模型给出的是处置意图，但规则读到了明确动作：以动作存在为准，修正为处置请求。
            return new IntentDecision(AgentIntent.ACTION_REQUEST, merged.confidence(), merged.topic(), merged.targetHint(),
                    merged.timeRange(), merged.requestedAction(), merged.reason() + "；文本中存在明确处置动作",
                    false, null);
        }
        return merged;
    }

    private static String trim(String contextSummary) {
        String text = contextSummary == null ? "" : contextSummary.trim();
        return text.length() > MAX_CONTEXT_SUMMARY_LENGTH ? text.substring(0, MAX_CONTEXT_SUMMARY_LENGTH) : text;
    }
}