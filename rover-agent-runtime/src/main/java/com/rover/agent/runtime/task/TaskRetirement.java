package com.rover.agent.runtime.task;

/**
 * 任务回收口：保留策略要删掉某个会话/事件下的任务时必须走这里，不能直接删任务记录。
 *
 * 一条任务记录牵着三样东西：任务快照（存储）、执行登记与事件通道（进程内）。
 * 只删存储会留下仍能被订阅的通道与执行态，因此回收统一由登记表完成。
 * 正在执行的任务不会被回收：它还在写自己的快照，删了会在下一次状态变更时"复活"，
 * 所以保留策略先用 hasActiveTasksXxx 判断，跳过还有任务在跑的会话与事件。
 */
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