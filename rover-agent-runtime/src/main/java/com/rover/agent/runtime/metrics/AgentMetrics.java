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

    /**
     * 一次模型调用：模型名（已规范化）、场景、耗时与结局。
     *
     * {@code outcome} 是有限枚举，因此失败可以按原因分开数（未配置 / 超时 / 报错 / 返回空 / 输出越界），
     * 而不是只有一个「失败了」——这是判断「模型辅助到底贡献了多少、又在哪一环丢的」的唯一依据。
     * 场景取有限取值（目标解析 / 调查规划 / 解读 / 对话），模型来源与用途分开看才不至于互相掩盖。
     */
    default void modelCall(String model, String scene, long durationMillis, ModelCallOutcome outcome) {
    }

    /**
     * 一次模型调用消耗的 token：只统计调用方拿到的用量，拿不到就不报（不猜、不补零）。
     *
     * 为什么要有它：调用次数与耗时只能说明「调了几次、等了多久」，回答不了「贵在哪、上下文是不是在膨胀」。
     * 尤其是每次调查都把快照写进提示词，输入 token 是成本的主要部分；分开记输入与输出才能看出
     * 是提示词变长了还是模型话多了。用量缺失（部分服务商流式不返回）时保持沉默，避免把「未知」记成 0。
     */
    default void modelTokens(String model, String scene, long promptTokens, long completionTokens) {
    }

    /** 事件流连接建立。 */
    default void sseConnected() {
    }

    /** 事件流连接关闭（正常收尾、断开或超时）。 */
    default void sseDisconnected() {
    }
}