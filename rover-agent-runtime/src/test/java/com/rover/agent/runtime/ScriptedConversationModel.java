package com.rover.agent.runtime;

import com.rover.agent.core.capability.CapabilityExecutor;
import com.rover.agent.runtime.llm.ChatModelGateway;
import com.rover.agent.runtime.llm.ConversationModel;
import com.rover.agent.runtime.tool.OpsTools;
import java.util.function.Consumer;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;

/**
 * 脚本化的对话模型：用「模型这一步做什么」替代真实模型调用。
 *
 * <p>为什么必须有它：主路径的行为由模型驱动，而真实模型的输出不可复现。但<b>编排职责</b>——
 * 工具是否落到真实取数、证据有没有随结论落库、预算耗尽怎样收尾、失败怎样收敛成可读结论——
 * 必须被单测覆盖。把模型侧换成脚本，这些职责才测得到。
 *
 * <p>而「模型会不会自己拆子问题、会不会选对工具」属于<b>模型能力</b>：拿一个自己写的脚本去断言
 * 它拆得好，只是在测自己的脚本。这一层由真实模型的端到端场景验证，不在单测里假装保证。
 */
final class ScriptedConversationModel implements ConversationModel {

    /** 一次对话的脚本：拿到工具，自己决定调哪些、返回什么回答。 */
    @FunctionalInterface
    interface Script {
        String answer(OpsTools tools);
    }

    private final Script script;
    private final String thinking;

    private ScriptedConversationModel(Script script, String thinking) {
        this.script = script;
        this.thinking = thinking;
    }

    /** 只产出回答的脚本。 */
    static ScriptedConversationModel answering(Script script) {
        return new ScriptedConversationModel(script, "");
    }

    /** 同时产出思考内容的脚本：用于验证思考与答案分两条流。 */
    static ScriptedConversationModel thinking(String thinking, Script script) {
        return new ScriptedConversationModel(script, thinking);
    }

    @Override
    public String converse(String systemPrompt, String userMessage, OpsTools tools,
                           Consumer<String> onDelta, Consumer<String> onThinking) {
        if (!thinking.isEmpty() && onThinking != null) {
            onThinking.accept(thinking);
        }
        String answer = script.answer(tools);
        if (answer != null && !answer.isEmpty() && onDelta != null) {
            // 一次性推送：与「上游一帧吐一大段」同形，正好覆盖打字机最需要处理的形态
            onDelta.accept(answer);
        }
        return answer;
    }

    /** 装配一个「模型声称可用 + 脚本作答」的对话服务，测试用它替换真实模型路径。 */
    static ToolLoopService service(CapabilityExecutor executor, Script script) {
        return new ToolLoopService(executor, gateway(), answering(script));
    }

    /** 带思考内容的对话服务。 */
    static ToolLoopService thinkingService(CapabilityExecutor executor, String thinking, Script script) {
        return new ToolLoopService(executor, gateway(), thinking(thinking, script));
    }

    /** 声称可用、但真实客户端永不被调用的网关：调用由脚本承担。 */
    static ChatModelGateway gateway() {
        return GATEWAY;
    }

    private static final ChatModelGateway GATEWAY = new ChatModelGateway() {
        @Override
        public boolean configured() {
            return true;
        }

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public ChatClient chatClient() {
            throw new UnsupportedOperationException("脚本模型不应被要求提供真实客户端");
        }

        @Override
        public ChatClient chatClient(int timeoutSeconds) {
            throw new UnsupportedOperationException("脚本模型不应被要求提供真实客户端");
        }

        @Override
        public String description() {
            return "scripted";
        }

        @Override
        public String reasoningDelta(ChatResponse response) {
            return "";
        }
    };
}
