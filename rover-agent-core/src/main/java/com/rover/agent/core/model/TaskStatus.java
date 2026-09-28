package com.rover.agent.core.model;

/** 调查任务状态。 */
public enum TaskStatus {

    /** 已创建，等待执行 */
    PENDING,

    /** 调查进行中 */
    RUNNING,

    /**
     * 等待用户补充输入：目标无法从问题与会话上下文确定，任务停在澄清点，不占用会话的并发位。
     *
     * 用户补充信息后由会话的下一次任务继续（本阶段不做同一任务的原地恢复）。
     */
    WAITING_INPUT,

    /**
     * 进程中断：任务在执行途中被重启打断，没有线程在跑，但已有步骤与证据都还在。
     *
     * 与 {@link #FAILED} 的区别在于「可不可以接着来」：中断的语义是「停在一个已知的安全恢复点」，
     * 已采集的事实保留下来，调查路径可以按恢复点续跑，人工会话可以就着这些事实重问一次。
     */
    INTERRUPTED,

    /** 调查完成，结论与证据已产出 */
    COMPLETED,

    /** 调查失败，只有错误说明没有结论 */
    FAILED,

    /** 被主动取消，尚未接入取消入口 */
    CANCELLED;

    /**
     * 是否仍在执行：占用会话并发位的状态。
     *
     * {@link #WAITING_INPUT} 不在此列——任务已停在澄清点、没有线程在跑，用户需要能继续提问。
     */
    public boolean active() {
        return this == PENDING || this == RUNNING;
    }

    /**
     * 是否已进入终态：状态不会再变化，结论或错误已成定局。
     *
     * {@link #INTERRUPTED} 也算终态——它已经停了，只是留下了一个可以接着跑的安全恢复点；
     * 续跑是「新一次执行」，不是这个任务自己恢复成执行中。
     */
    public boolean terminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED || this == INTERRUPTED;
    }
}