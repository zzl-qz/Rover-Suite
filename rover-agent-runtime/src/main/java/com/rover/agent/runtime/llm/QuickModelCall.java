package com.rover.agent.runtime.llm;

import com.rover.agent.runtime.metrics.ModelCallOutcome;
import com.rover.agent.runtime.task.AgentRunBudget;
import java.io.InterruptedIOException;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;

/** 目标解析和规划的模型调用策略：短超时上限，仅对超时重试一次。 */
final class QuickModelCall {

    private static final Logger log = LoggerFactory.getLogger(QuickModelCall.class);

    private QuickModelCall() {
    }

    /**
     * 发一次调用；超时且 {@code timeoutSeconds > 0} 时用同样的上限再试一次。
     *
     * @return 模型返回的文本（可能为 null）；两次都失败则抛出后一次异常，由调用方决定如何兜底
     */
    static String content(ChatModelGateway gateway, int timeoutSeconds, String scene,
                          Function<ChatClient, String> call) {
        try {
            return call.apply(AgentBudgetAdvisor.client(gateway.chatClient(timeoutSeconds),
                    AgentRunBudget.current(), gateway::reasoningDelta));
        } catch (RuntimeException failure) {
            if (timeoutSeconds <= 0 || !timedOut(failure)) {
                throw failure;
            }
            log.warn("Agent 模型调用超时（{}），重试一次", scene);
            return call.apply(AgentBudgetAdvisor.client(gateway.chatClient(timeoutSeconds),
                    AgentRunBudget.current(), gateway::reasoningDelta));
        }
    }

    /**
     * 把一次失败的调用归类：超时归 TIMEOUT，其余归 ERROR。
     *
     * 两者要分开数：超时是链路与容量问题，重试与服务端扩容有意义；ERROR 是凭证、模型名、参数问题，
     * 重试不会变好。调用方按这个分类决定日志级别与指标标签，不必再自己判异常链。
     */
    static ModelCallOutcome classify(Throwable failure) {
        return timedOut(failure) ? ModelCallOutcome.TIMEOUT : ModelCallOutcome.ERROR;
    }

    /** 沿异常链找超时：okhttp 的超时最终是 InterruptedIOException("timeout")，SocketTimeoutException 是其子类。 */
    private static boolean timedOut(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof InterruptedIOException) {
                return true;
            }
        }
        return false;
    }
}
