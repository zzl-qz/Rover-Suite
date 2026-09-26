package com.rover.agent.runtime.metrics;

/**
 * Agent 运行指标端口：任务、模型与事件流的轻量计数。
 *
 * 只有事件式的增量上报，没有读方法——运行层不关心指标写到哪、有没有被采集，
 * 由宿主进程决定实现（Admin 用 Micrometer 的内存注册表，未装配时用 {@link #NOOP}）。
 *
 * 上报本身必须廉价且不阻塞：全部实现都只做计数与计时，不做网络 IO，也不进入主转发路径。
 * 标签里一律不放用户问题、请求路径、会话 ID 与任务 ID：它们基数不可控，
 * 需要定位具体任务时应回到任务快照与事件流。
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

    /** 一次模型解读调用：模型名（已规范化）、耗时与是否失败。 */
    default void modelCall(String model, long durationMillis, boolean failed) {
    }

    /** 事件流连接建立。 */
    default void sseConnected() {
    }

    /** 事件流连接关闭（正常收尾、断开或超时）。 */
    default void sseDisconnected() {
    }
}