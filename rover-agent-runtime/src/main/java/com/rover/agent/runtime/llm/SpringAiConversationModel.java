package com.rover.agent.runtime.llm;

import com.rover.agent.runtime.metrics.AgentMetrics;
import com.rover.agent.runtime.metrics.ModelCallOutcome;
import com.rover.agent.runtime.tool.OpsTools;
import java.util.function.Consumer;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

/**
 * 基于 Spring AI function calling 的对话实现：工具调用循环由框架承担。
 *
 * 一次 {@code stream()} 调用内部会跑完整个循环——模型返回工具调用、框架执行工具、结果回灌、
 * 模型继续判断，直到它给出最终回答。本类只做三件框架不做的事：把文本增量与思考增量分开推给调用方、
 * 跳过「中间轮次」的文本（那一轮模型在自言自语「我先查一下路由」，混进结论里会变成半句话）、
 * 记录用量与结局。
 *
 * 厂商差异仍收在 {@link ChatModelGateway#reasoningDelta} 那一层：本类只问「这一帧有没有思考内容」，
 * 因此换模型、换服务商都不影响这里的逻辑。
 */
public final class SpringAiConversationModel implements ConversationModel {

    /** 回答长度上限：主回答比解读宽松，但必须有界，否则一次失控的输出会把任务快照撑爆。 */
    private static final int MAX_ANSWER_LENGTH = 4000;

    /** 思考文本的长度上限：比回答略宽——推理过程本来就比结论长。 */
    private static final int MAX_THINKING_LENGTH = 4000;

    /** 指标里的场景名：与「意图识别 / 目标解析 / 解读」并列，便于按用途分开看模型表现。 */
    private static final String SCENE = "对话";

    private final ChatModelGateway gateway;
    private final AgentMetrics metrics;

    public SpringAiConversationModel(ChatModelGateway gateway) {
        this(gateway, AgentMetrics.NOOP);
    }

    public SpringAiConversationModel(ChatModelGateway gateway, AgentMetrics metrics) {
        this.gateway = gateway == null ? new NoopChatModelGateway() : gateway;
        this.metrics = metrics == null ? AgentMetrics.NOOP : metrics;
    }

    @Override
    public String converse(String systemPrompt, String userMessage, OpsTools tools,
                           Consumer<String> onDelta, Consumer<String> onThinking) {
        StringBuilder answer = new StringBuilder();
        StringBuilder thinking = new StringBuilder();
        long startedAt = System.nanoTime();
        ModelCallOutcome outcome = ModelCallOutcome.OK;
        try {
            gateway.chatClient().prompt()
                    .system(systemPrompt)
                    .user(userMessage)
                    .tools(tools)
                    .stream()
                    // 用 chatResponse 而不是 content：文本照旧逐段取，但顺带拿到思考增量与用量，
                    // 也能从每帧是否携带工具调用来区分「中间轮次」与「最终回答」。
                    .chatResponse()
                    .doOnNext(response -> collect(response, answer, thinking, onDelta, onThinking))
                    .blockLast();
            if (answer.isEmpty()) {
                outcome = ModelCallOutcome.EMPTY;
            }
            return answer.toString().trim();
        } catch (RuntimeException ex) {
            outcome = QuickModelCall.classify(ex);
            throw ex;
        } finally {
            // 只上报耗时、结局与用量，不记提示词与回答：指标里不出现业务内容，也不出现密钥。
            metrics.modelCall(gateway.description(), SCENE,
                    Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L), outcome);
        }
    }

    /**
     * 累积一帧响应。
     *
     * 携带工具调用的那一帧属于「中间步骤」而不是回答，跳过文本累积：过程本身由工具步骤展示，
     * 而模型在这里说的半句话混进结论会让回答读起来像是从中间开始的。思考增量不受此限，
     * 它本来就是关于过程的描述。
     */
    private void collect(ChatResponse response, StringBuilder answer, StringBuilder thinking,
                         Consumer<String> onDelta, Consumer<String> onThinking) {
        if (response == null) {
            return;
        }
        Generation generation = response.getResult();
        AssistantMessage message = generation == null ? null : generation.getOutput();
        if (message != null && !message.hasToolCalls()) {
            String chunk = message.getText();
            if (chunk != null && !chunk.isEmpty() && answer.length() < MAX_ANSWER_LENGTH) {
                int room = MAX_ANSWER_LENGTH - answer.length();
                String piece = chunk.length() > room ? chunk.substring(0, room) : chunk;
                answer.append(piece);
                if (onDelta != null) {
                    onDelta.accept(piece);
                }
            }
        }
        String thought = gateway.reasoningDelta(response);
        if (!thought.isEmpty() && thinking.length() < MAX_THINKING_LENGTH) {
            int room = MAX_THINKING_LENGTH - thinking.length();
            String piece = thought.length() > room ? thought.substring(0, room) : thought;
            thinking.append(piece);
            if (onThinking != null) {
                onThinking.accept(piece);
            }
        }
        tokens(response);
    }

    /**
     * 用量上报：保留「最后一次非空值」而不是求和——部分服务商每帧都报累计值，求和会得到成倍的假数字。
     * 整段都没给（有些服务商流式不返回用量）时保持沉默：把「未知」记成 0 会让成本看起来比实际低。
     */
    private void tokens(ChatResponse response) {
        ChatResponseMetadata metadata = response.getMetadata();
        Usage usage = metadata == null ? null : metadata.getUsage();
        if (usage == null) {
            return;
        }
        Integer prompt = usage.getPromptTokens();
        Integer completion = usage.getCompletionTokens();
        if (prompt == null && completion == null) {
            return;
        }
        metrics.modelTokens(gateway.description(), SCENE, prompt == null ? 0 : prompt,
                completion == null ? 0 : completion);
    }
}
