package com.rover.agent.runtime.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.rover.agent.core.capability.AgentGrounding;
import com.rover.agent.core.capability.CapabilityRegistry;
import com.rover.agent.core.capability.UntrustedText;
import com.rover.agent.core.intent.IntentInterpreter;
import com.rover.agent.core.model.ActionType;
import com.rover.agent.core.model.AgentIntent;
import com.rover.agent.core.model.Confidence;
import com.rover.agent.core.model.IntentDecision;
import com.rover.agent.core.model.IntentTopic;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 模型辅助的意图解释器：把「用户想干什么」的判断交给模型，但只接受结构化的有限取值。
 *
 * 提示词由三部分组成，缺一不可：
 * <ol>
 *   <li><b>环境画像</b>（{@link AgentGrounding}）：系统由什么组成、一次调用经过哪几环、用户口语对应哪个系统概念。
 *       模型判断「换个说法问同一件事」时靠的就是这份对照，而不是从词面猜；</li>
 *   <li><b>能力清单</b>（{@link CapabilityRegistry#describe()}）：Agent 实际能读什么、边界在哪，
 *       免得把「帮我看看日志」这类请求归到某个并不存在的能力上；</li>
 *   <li><b>取值与判别顺序</b>：每个意图含义、同时像两类时的优先级、以及少量示例。</li>
 * </ol>
 *
 * 模型输出用 {@link ModelJson} 取出、按 {@link #parseDecision} 的契约整条校验：四个字段缺一个或越界一个
 * 就整条丢弃（不补默认值），由 {@link SpringAiJsonCompletion} 记成「输出不合契约」并按场景计数，
 * 再由 {@link com.rover.agent.core.intent.IntentService} 回退到规则分类。
 * 目标线索与时间范围不在这里判断，它们由规则从文本直接读出来后再合并——意图与目标严格分离。
 */
public final class LlmIntentInterpreter implements IntentInterpreter {

    private static final Logger log = LoggerFactory.getLogger(LlmIntentInterpreter.class);

    private static final String SYSTEM_PROMPT = "你是 Rover Ops Agent 的意图识别模块。"
            + "只判断用户想做什么，不判断对象是否存在、不猜测资源名称、也不回答用户的问题本身。"
            + "只输出一个 JSON 对象，不要输出解释文字，也不要用代码块包裹。"
            + UntrustedText.contract();

    private static final int MAX_QUESTION_LENGTH = 1000;
    private static final int MAX_CONTEXT_LENGTH = 600;

    private final JsonCompletion completion;
    private final CapabilityRegistry capabilities;

    public LlmIntentInterpreter(JsonCompletion completion, CapabilityRegistry capabilities) {
        this.completion = completion;
        this.capabilities = capabilities == null ? CapabilityRegistry.standard() : capabilities;
    }

    @Override
    public Optional<IntentDecision> interpret(String question, String contextSummary) {
        String asked = question == null ? "" : question.trim();
        if (asked.isEmpty()) {
            return Optional.empty();
        }
        if (asked.length() > MAX_QUESTION_LENGTH) {
            asked = asked.substring(0, MAX_QUESTION_LENGTH);
        }
        String context = contextSummary == null ? "" : contextSummary.trim();
        if (context.length() > MAX_CONTEXT_LENGTH) {
            context = context.substring(0, MAX_CONTEXT_LENGTH);
        }
        return completion.complete(SYSTEM_PROMPT, userPrompt(asked, context), LlmIntentInterpreter::parseDecision);
    }

    private String userPrompt(String question, String context) {
        StringBuilder prompt = new StringBuilder();
        prompt.append(AgentGrounding.environment()).append('\n')
                .append(AgentGrounding.glossary()).append('\n')
                .append(capabilities.describe()).append('\n')
                .append(UntrustedText.block("用户消息", question));
        if (!context.isBlank()) {
            prompt.append(UntrustedText.block("会话上下文（只用于理解「它 / 那个 / 现在呢」这类指代，"
                    + "不要据此改变用户要问的对象）", context));
        }
        prompt.append("\n【意图取值】\n")
                .append("- CREATE_INSPECTION：要求创建定时或周期性的巡检 / 报告 / 通知（每天、每周、定期、自动巡检）。\n")
                .append("- ACTION_REQUEST：要求对某个对象执行操作（摘除、下线、踢掉、上线、恢复、扩容、改超时、改限流）。\n")
                .append("- EXPLAIN：要求解释、总结已经有的信息（结论、上文），或问 Agent 自身的能力与用法。\n")
                .append("- INVESTIGATE：追问失败、异常、变慢、不可用的原因，或在要求排查（为什么、怎么回事、是不是有问题）。\n")
                .append("- QUERY_STATE：只问当前事实——数量、健康状态、QPS / 流量 / 错误率、路由指向、"
                        + "当前生效的配置值、最近的注册事件；要的是「是多少 / 有没有 / 在哪」，不是「为什么」。\n")
                .append("- KNOWLEDGE_QUERY：问文档、教程、怎么接入这类知识内容。\n")
                .append("- UNKNOWN：与运维完全无关（闲聊、天气、寒暄、常识）。\n")
                .append("【同时像两类时按此优先级】CREATE_INSPECTION > ACTION_REQUEST > EXPLAIN > INVESTIGATE > "
                        + "QUERY_STATE > KNOWLEDGE_QUERY。\n")
                .append("【拿不准时怎么办】只要问题与上面的系统有关，就必须在可选项中选最接近的一类，"
                        + "并如实给 MEDIUM 或 LOW；只有确实与运维毫无关系时才可以用 UNKNOWN。"
                        + "说不清对象是谁不算说不清意图——对象是否存在是后续步骤的事。\n")
                .append("【topic 取值】NONE 无细分 / CAPABILITIES 问系统能力 / INCIDENT 解释当前事件或上文 / GENERAL 一般解释。\n")
                .append("【action 取值】仅当意图为 ACTION_REQUEST 时有意义：DRAIN_INSTANCE 摘除·下线·踢掉实例 / "
                        + "RESTORE_INSTANCE 上线·恢复·扩容 / UPDATE_ROUTE_TIMEOUT 改超时 / "
                        + "UPDATE_RATE_LIMIT 改限流或熔断 / UNKNOWN 说不清具体动作。其余意图填 UNKNOWN。\n")
                .append("【confidence 取值】HIGH 语义明确无歧义 / MEDIUM 大致确定 / LOW 只是最接近的猜测。\n")
                .append("【示例】\n")
                .append("- 「网关现在 QPS 多少」→ {\"intent\":\"QUERY_STATE\",\"confidence\":\"HIGH\",\"topic\":\"NONE\",\"action\":\"UNKNOWN\",\"reason\":\"问当前流量事实\"}\n")
                .append("- 「接口响应好慢，是不是哪儿出问题了」→ {\"intent\":\"INVESTIGATE\",\"confidence\":\"HIGH\",\"topic\":\"NONE\",\"action\":\"UNKNOWN\",\"reason\":\"追问变慢的原因\"}\n")
                .append("- 「order-service 咋回事啊」→ {\"intent\":\"INVESTIGATE\",\"confidence\":\"MEDIUM\",\"topic\":\"NONE\",\"action\":\"UNKNOWN\",\"reason\":\"口语化的故障问法\"}\n")
                .append("- 「帮我看下 /api/order 现在指向哪个服务」→ {\"intent\":\"QUERY_STATE\",\"confidence\":\"HIGH\",\"topic\":\"NONE\",\"action\":\"UNKNOWN\",\"reason\":\"问路由指向\"}\n")
                .append("- 「把 order-03 摘了」→ {\"intent\":\"ACTION_REQUEST\",\"confidence\":\"HIGH\",\"topic\":\"NONE\",\"action\":\"DRAIN_INSTANCE\",\"reason\":\"要求摘除实例\"}\n")
                .append("- 「上次那个结论再解释一下」→ {\"intent\":\"EXPLAIN\",\"confidence\":\"HIGH\",\"topic\":\"INCIDENT\",\"action\":\"UNKNOWN\",\"reason\":\"要求解释已有结论\"}\n")
                .append("- 「今天天气怎么样」→ {\"intent\":\"UNKNOWN\",\"confidence\":\"HIGH\",\"topic\":\"NONE\",\"action\":\"UNKNOWN\",\"reason\":\"与运维无关\"}\n")
                .append("【输出】只输出一个 JSON 对象："
                        + "{\"intent\":\"...\",\"confidence\":\"...\",\"topic\":\"...\",\"action\":\"...\",\"reason\":\"一句话依据\"}");
        return prompt.toString();
    }

    /**
     * 输出契约：四个字段必须齐全且在枚举取值范围内，缺一个或越界一个就整条丢弃。
     *
     * 为什么不「缺省补一个安全值」：补默认值看起来更宽容，实际是把不可信输出悄悄当成可信判断用——
     * 模型给了个不存在的 confidence，补成 MEDIUM 之后这条判断照样会被采用，错误就顺着下游走了。
     * 整条丢弃则回到规则分类这条确定性路径，代价只是少一次模型建议，不会引入错误判断。
     * 越界时只记字段名（不记模型原文），便于发现提示词与契约脱节，又不把输出内容写进日志。
     */
    private static Optional<IntentDecision> parseDecision(String raw) {
        Optional<JsonNode> parsed = ModelJson.object(raw);
        if (parsed.isEmpty()) {
            log.info("意图识别输出不是合法 JSON 对象，整条丢弃");
            return Optional.empty();
        }
        JsonNode node = parsed.get();
        AgentIntent intent = intent(ModelJson.text(node, "intent"));
        Confidence confidence = confidence(ModelJson.text(node, "confidence"));
        IntentTopic topic = topic(ModelJson.text(node, "topic"));
        ActionType action = action(ModelJson.text(node, "action"));
        String missing = contractViolation(intent, confidence, topic, action);
        if (missing != null) {
            log.info("意图识别输出越界，整条丢弃：字段={}", missing);
            return Optional.empty();
        }
        String reason = ModelJson.text(node, "reason");
        return Optional.of(new IntentDecision(intent, confidence, topic, "", null, action,
                reason.isBlank() ? "模型意图判断" : reason, false, null));
    }

    /** 返回第一个不合契约的字段名；全部合规时返回 null。 */
    private static String contractViolation(AgentIntent intent, Confidence confidence,
                                            IntentTopic topic, ActionType action) {
        if (intent == null) {
            return "intent";
        }
        if (confidence == null) {
            return "confidence";
        }
        if (topic == null) {
            return "topic";
        }
        return action == null ? "action" : null;
    }

    /** 取值必须在枚举内；缺失或越界都返回 null（由 {@link #contractViolation} 判定并丢弃整条输出）。 */
    private static AgentIntent intent(String name) {
        return valueOf(AgentIntent.class, name);
    }

    private static Confidence confidence(String name) {
        return valueOf(Confidence.class, name);
    }

    private static IntentTopic topic(String name) {
        return valueOf(IntentTopic.class, name);
    }

    private static ActionType action(String name) {
        return valueOf(ActionType.class, name);
    }

    private static <E extends Enum<E>> E valueOf(Class<E> type, String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, name.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
