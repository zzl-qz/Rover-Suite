package com.rover.agent.runtime.task;

/**
 * Agent 执行参数：工作线程数、执行队列容量与任务登记容量。
 *
 * 参数必须显式配置（{@code rover.agent.execution.*}）而不是散落硬编码：不同部署环境的调查压力差异很大，
 * 但边界是固定的——不使用无界队列，也不允许 0 线程或超出上限的队列。取值越界时构造即失败，
 * 让配置错误在启动阶段暴露，而不是在运行期以「任务莫名被拒」的形式出现。
 *
 * @param workerThreads  调查工作线程数；模型与只读端口的阻塞调用只允许占用这些线程
 * @param queueCapacity  执行队列容量；队列满时提交被拒（HTTP 429），绝不无界堆积
 * @param taskCapacity   任务登记容量；已结束任务可被淘汰，仍在执行的任务不会因容量被丢弃
 */
public record AgentExecutionSettings(int workerThreads, int queueCapacity, int taskCapacity) {

    /** 工作线程边界：至少 1 个；上限防止线程耗尽机器。 */
    public static final int MIN_WORKER_THREADS = 1;
    public static final int MAX_WORKER_THREADS = 32;

    /** 队列边界：至少 1 个排队位；上限防止提交积压把内存吃满。 */
    public static final int MIN_QUEUE_CAPACITY = 1;
    public static final int MAX_QUEUE_CAPACITY = 1000;

    /** 登记容量边界：至少要能容纳一轮并发（线程 + 队列），上限防止内存中的任务快照无限增长。 */
    public static final int MIN_TASK_CAPACITY = 1;
    public static final int MAX_TASK_CAPACITY = 10000;

    private static final AgentExecutionSettings DEFAULTS = new AgentExecutionSettings(2, 16, 200);

    public AgentExecutionSettings {
        requireRange("worker-threads", workerThreads, MIN_WORKER_THREADS, MAX_WORKER_THREADS);
        requireRange("queue-capacity", queueCapacity, MIN_QUEUE_CAPACITY, MAX_QUEUE_CAPACITY);
        requireRange("task-capacity", taskCapacity, MIN_TASK_CAPACITY, MAX_TASK_CAPACITY);
    }

    /** 默认值：2 个工作线程、16 个排队位、200 条任务登记（与既有行为一致）。 */
    public static AgentExecutionSettings defaults() {
        return DEFAULTS;
    }

    private static void requireRange(String name, int value, int min, int max) {
        if (value < min || value > max) {
            throw new IllegalArgumentException("rover.agent.execution." + name + " 必须在 " + min
                    + " 到 " + max + " 之间，当前为 " + value);
        }
    }
}