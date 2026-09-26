package com.rover.agent.runtime.task;

/**
 * 会话并发冲突：同一会话已有一个仍在执行（PENDING / RUNNING）的调查任务。
 *
 * 并发约束的动机是一致性而不是限流：同一事件上的两个任务并发时，谁后完成谁覆盖事件摘要，
 * 甚至会出现「任务 A 完成把事件置为已结论、而任务 B 仍在跑」的错误状态。
 * 等待用户输入的 WAITING_INPUT 不算执行中，用户仍然可以继续提问。
 *
 * 由 HTTP 契约层映射为 409 Conflict。
 */
public class SessionTaskRunningException extends RuntimeException {

    private final String runningTaskId;

    public SessionTaskRunningException(String runningTaskId) {
        super("当前会话仍有调查任务执行中，请等待完成或取消后继续。");
        this.runningTaskId = runningTaskId;
    }

    /** 正在执行的任务 ID，供前端定位到那张任务卡。 */
    public String runningTaskId() {
        return runningTaskId;
    }
}