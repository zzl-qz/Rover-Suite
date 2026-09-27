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
 * <p>与「先归类意图、再按固定流程取数」的区别在于<b>决策权的位置</b>。这里不再有任何
 * 「这句话属于哪一类」的判断：用户问什么，模型自己看；需要哪些事实，模型自己调工具去取；
 * 一句话里问了多件事，模型自己拆开一个个处理。工具是可执行的真实查询，所以「先看实例、
 * 发现某台不健康、再看它的指标」这种链式判断才可能发生——固定流程做不到这一点。
 *
 * <p>本类只管编排职责：工具从哪里来、证据往哪落、步骤怎么展示、预算耗尽与模型失败如何收敛。
 * 「怎么跟模型说话」交给 {@link ConversationModel}，「怎么取数」交给 {@link OpsTools}，
 * 两者都可替换，因此这些编排职责可以被单测完整覆盖。
 *
 * <p>边界一条没少：工具全部只读且共用同一个执行器，调用次数有上限，越限不是静默丢弃而是
 * 明确告知模型收尾；模型不可用时如实说明，不做「看起来还能用」的假降级。
 */
public final class ToolLoopService {

    private static final Logger log = LoggerFactory.getLogger(ToolLoopService.class);

    private static final String STEP_ANSWER = "回答";
    private static final String ANSWER_RUNNING = "正在判断需要查什么，并按问题组织回答";
    private static final String NO_MODEL =
            "当前没有配置可用的模型，Ops Agent 无法理解问题与组织回答。"
                    + "请在「模型配置」里填入可用的模型地址与密钥后重试。";

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 对话提示词：把「怎么问、怎么查、怎么答」讲清楚，其余交给模型。
     *
     * <p>四条纪律各自对应一类真实翻车：不拆子问题会让多问句只答一半；不区分「没查到」与
     * 「没有问题」会把缺失数据说成正常；不带口径引用会让结论无法复核；把无关提问硬塞进工具
     * 会让「你好」也去查一遍注册中心。环境画像与术语表沿用与其它环节同一份文本，
     * 模型对「卡」「挂了」这些用户说法的理解因此保持一致。
     */
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
            log.warn("Agent 对话执行失败", ex);
            String reason = failureText(ex, tools);
            task.step(AgentStepType.ANSWER, STEP_ANSWER, StepStatus.FAILED, reason);
            task.fail(reason);
        }
    }

    /**
     * 用户消息：当前时间 + 会话背景 + 提问本身。
     *
     * <p>时间由运行时给出而不是让模型猜：问「现在是什么时候」时它不必承认不知道，
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
