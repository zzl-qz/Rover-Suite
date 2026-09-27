package com.rover.agent.runtime;

import com.rover.agent.core.capability.AgentGrounding;
import com.rover.agent.core.capability.CapabilityExecutor;
import com.rover.agent.core.capability.UntrustedText;
import com.rover.agent.core.model.AgentStepType;
import com.rover.agent.core.model.Confidence;
import com.rover.agent.core.model.InvestigationReport;
import com.rover.agent.core.model.StepStatus;
import com.rover.agent.runtime.llm.ChatModelGateway;
import com.rover.agent.runtime.llm.ConversationModel;
import com.rover.agent.runtime.llm.NoopChatModelGateway;
import com.rover.agent.runtime.llm.SpringAiConversationModel;
import com.rover.agent.runtime.task.InvestigationTask;
import com.rover.agent.runtime.tool.OpsTools;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 对话主路径：模型自主决定查什么、查几次，并逐个回答问题。
 *
 * 本类只管编排职责：工具从哪里来、证据往哪落、预算耗尽与模型失败如何收敛。
 * 模型侧与工具侧都可替换，因此这些职责能被单测完整覆盖。
 */
public final class ToolLoopService {

    private static final Logger log = LoggerFactory.getLogger(ToolLoopService.class);

    private static final String STEP_ANSWER = "回答";
    private static final String ANSWER_RUNNING = "正在判断需要查什么，并按问题组织回答";
    private static final String NO_MODEL =
            "当前没有配置可用的模型，Ops Agent 无法理解问题与组织回答。"
                    + "请在「模型配置」里填入可用的模型地址与密钥后重试。";

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 对话提示词：讲清怎么问、怎么查、怎么答，其余交给模型。 */
    private static final String SYSTEM_PROMPT = "你是 Rover Ops Agent——一个只读的网关运维诊断助手，"
            + "挂在 API 网关和注册中心上，用中文回答。\n"
            + AgentGrounding.environment() + "\n" + AgentGrounding.glossary() + "\n"
            + "【你有哪些工具】你可以调用一组只读工具去查真实数据：路由配置、注册实例、网关指标"
            + "（含按上游实例的窗口观测）、抽样追踪、生效配置、注册事件。"
            + "它们返回的是真实快照，并带统计窗口与样本量。除了工具返回的事实和你自己的推理，"
            + "你没有别的信息来源。\n"
            + "【怎么回答】\n"
            + "1. 先看清用户这句话里其实有几个问题。一句话问了多件事时，逐个处理，不要只答其中一件。\n"
            + "2. 每个问题需要查什么由你自己判断并调用工具；不要假设数据已经给你了，也不要因为"
            + "「不确定该查哪个」就放弃。信息不足时先查一个最可能相关的，再根据结果决定下一步。\n"
            + "3. 能回答的问题必须答清楚，并说明依据来自哪份数据。\n"
            + "4. 某个问题查不到答案时，直接说明「这一部分没有查到」，并说清你查了什么、缺的是什么；"
            + "不要因为一个问题答不了就放弃其他问题，也不要编一个看起来合理的答案。\n"
            + "5. 与网关运维无关的提问（问候、闲聊、时间等），直接如实回答即可，不需要调用任何工具。\n"
            + "【回答风格】先给结论，再给依据，不要先铺垫一大段；用日常说法讲，少堆术语；"
            + "用户没问的细节不要展开——简单问题一两句说完，不要为了显得专业而写长。\n"
            + "【事实纪律】\n"
            + "- 引用指标必须带上统计窗口与样本量；样本不足时说「样本不足，无法判断」，不要说成已确认。\n"
            + "- 窗口内没有记录不等于没有问题，要如实说「没有采集到」，而不是「一切正常」。\n"
            + "- 你是只读的：不要声称已经执行修复，也不要输出任何执行命令。\n"
            + UntrustedText.contract();

    private final CapabilityExecutor executor;
    private final ChatModelGateway gateway;
    private final ConversationModel model;

    public ToolLoopService(CapabilityExecutor executor, ChatModelGateway gateway) {
        this(executor, gateway, null);
    }

    /**
     * @param model 模型侧实现；为 {@code null} 时使用基于 Spring AI 的默认实现
     */
    public ToolLoopService(CapabilityExecutor executor, ChatModelGateway gateway, ConversationModel model) {
        this.executor = executor;
        this.gateway = gateway == null ? new NoopChatModelGateway() : gateway;
        this.model = model == null
                ? new SpringAiConversationModel(this.gateway)
                : model;
    }

    /** 是否已配置模型；未配置时对话主路径无法工作，这一点会如实告知用户。 */
    public boolean configured() {
        return gateway.configured();
    }

    public boolean available() {
        return gateway.available();
    }

    public String modelDescription() {
        return gateway.description();
    }

    /**
     * 执行一次对话：模型自主查询、自主作答，答案边产生边推送。
     *
     * @param task           承载步骤、证据与结论的任务
     * @param contextSummary 会话上下文摘要（上一轮问了什么、当前对象是谁）；只作为背景，不当作事实
     */
    public void run(InvestigationTask task, String contextSummary) {
        if (!gateway.available()) {
            // 不做「看起来还能用」的假降级：这条路径的每一次取数与每一句结论都出自模型，
            // 没有模型时诚实的回复是「现在不能用」，而不是回退到另一套规则流程冒充同一个 Agent。
            task.step(AgentStepType.ANSWER, STEP_ANSWER, StepStatus.FAILED, NO_MODEL);
            task.complete(new InvestigationReport(NO_MODEL, Confidence.LOW, List.of(), List.of(NO_MODEL),
                    List.of(), null));
            return;
        }
        OpsTools tools = new OpsTools(executor, task);
        try {
            task.step(AgentStepType.ANSWER, STEP_ANSWER, StepStatus.RUNNING, ANSWER_RUNNING);
            String answer = model.converse(SYSTEM_PROMPT, userMessage(task.question(), contextSummary), tools,
                    task::appendAnalysis, task::appendThinking);
            if (task.cancelled()) {
                // 模型回答过程中被取消：不产出结论，CANCELLED 状态已由取消方发布。
                return;
            }
            if (answer == null || answer.isBlank()) {
                String reason = "模型没有返回任何回答内容。";
                task.step(AgentStepType.ANSWER, STEP_ANSWER, StepStatus.FAILED, reason);
                task.fail(reason);
                return;
            }
            task.step(AgentStepType.ANSWER, STEP_ANSWER, StepStatus.COMPLETED,
                    tools.callCount() == 0
                            ? "未查询任何数据，直接回答（与网关状态无关的提问）"
                            : "回答完成，本次共取数 " + tools.callCount() + " 次");
            // 结论由模型组织，因此置信度如实记为 MEDIUM：事实有据可查，推理仍出自模型。
            task.complete(new InvestigationReport(answer, Confidence.MEDIUM, tools.evidence(),
                    tools.limitations(), List.of(), null));
        } catch (Exception ex) {
            if (task.cancelled()) {
                // 取消过程中断：结论已被标记 CANCELLED，不要覆盖成失败。
                return;
            }
            log.warn("Agent 对话执行失败", ex);
            String reason = failureText(ex, tools);
            task.step(AgentStepType.ANSWER, STEP_ANSWER, StepStatus.FAILED, reason);
            task.fail(reason);
        }
    }

    /**
     * 用户消息：当前时间 + 会话背景 + 提问本身。
     *
     * 时间由运行时给出而不是让模型猜：问「现在是什么时候」时它不必承认不知道，
     * 而时间在诊断里是有用的事实——判断事件发生在多久前、指标窗口覆盖到哪。
     */
    private static String userMessage(String question, String contextSummary) {
        StringBuilder message = new StringBuilder("【当前时间】").append(LocalDateTime.now().format(STAMP)).append('\n');
        String context = contextSummary == null ? "" : contextSummary.trim();
        if (!context.isEmpty()) {
            message.append(UntrustedText.block("会话背景", context));
        }
        message.append(UntrustedText.block("用户提问", question));
        return message.toString();
    }

    /** 失败说明：把「哪一步出问题」说清楚，并保住已经取到的数据，而不是笼统报一句失败。 */
    private static String failureText(Exception ex, OpsTools tools) {
        String reason = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        return tools.callCount() == 0
                ? "模型调用失败，本次没有取到任何数据：" + reason
                : "模型调用失败（已取数 " + tools.callCount() + " 次，这些数据仍可在步骤里查看）：" + reason;
    }
}
