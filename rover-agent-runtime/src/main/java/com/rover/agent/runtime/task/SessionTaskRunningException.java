package com.rover.agent.runtime.task;

/**
 * 同会话已有 PENDING/RUNNING 任务，映射为 HTTP 409。
 * WAITING_INPUT 不视为执行中。
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