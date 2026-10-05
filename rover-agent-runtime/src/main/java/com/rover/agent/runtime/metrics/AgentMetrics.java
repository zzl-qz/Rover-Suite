package com.rover.agent.runtime.metrics;

/**
 * Agent 任务、模型和事件流的增量指标端口，由宿主实现或使用 NOOP。
 * 上报须廉价且不阻塞；标签不包含用户问题、路径、会话或任务 ID。
 */
public interface AgentMetrics {

    /** 什么都不做的实现：指标关闭或未装配注册表时使用。 */
    AgentMetrics NOOP = new AgentMetrics() { };

    /** 任务登记成功（PENDING）。 */
    default void taskSubmitted() {
    }

    /** 任务开始执行（进入 RUNNING）。 */
    default void taskStarted() {
    }

    /** 登记那一刻的等待队列深度：队列入队后采样一次，不做后台轮询。 */
    default void taskQueued(int queueSize) {
    }

    /**
     * 任务结束：终态名与从登记到结束的耗时。
     *
     * {@code status} 取任务终态（COMPLETED / FAILED / WAITING_INPUT），因此这里只记耗时，
     * 计数按终态语义分到完成与失败两条线上（见实现类）。
     */
    default void taskSettled(String status, long durationMillis) {
    }

    /** 提交被拒：{@code reason} 只取有限枚举（SESSION_BUSY / CAPACITY / QUEUE）。 */
    default void taskRejected(String reason) {
    }

    /** 记录模型调用的名称、场景、耗时与有限枚举结局。 */
    default void modelCall(String model, String scene, long durationMillis, ModelCallOutcome outcome) {
    }

    /** 分别记录模型输入与输出 token；未返回用量时不猜测或补零。 */
    default void modelTokens(String model, String scene, long promptTokens, long completionTokens) {
    }

    /** 事件流连接建立。 */
    default void sseConnected() {
    }

    /** 事件流连接关闭（正常收尾、断开或超时）。 */
    default void sseDisconnected() {
    }
}