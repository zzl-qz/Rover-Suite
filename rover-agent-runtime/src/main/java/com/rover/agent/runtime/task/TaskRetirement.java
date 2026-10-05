package com.rover.agent.runtime.task;

/** 统一回收任务快照、执行登记与事件通道；执行中任务不回收。 */
public interface TaskRetirement {

    /** 该会话下是否还有执行中的任务（PENDING / RUNNING）。 */
    boolean hasActiveTasksInSession(String sessionId);

    /** 该事件下是否还有执行中的任务。 */
    boolean hasActiveTasksInIncident(String incidentId);

    /** 回收会话下的全部任务，返回回收条数。 */
    int retireBySession(String sessionId);

    /** 回收事件下的全部任务，返回回收条数。 */
    int retireByIncident(String incidentId);
}