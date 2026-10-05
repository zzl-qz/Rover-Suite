package com.rover.agent.core.repository;

import com.rover.agent.core.model.AgentCheckpoint;
import com.rover.agent.core.model.TaskView;
import java.util.List;
import java.util.Optional;

/** 安全恢复点存储契约；任务状态、步骤、证据与恢复点必须在同次写入中保存。 */
public interface AgentCheckpointRepository {

    /**
     * 推进到一个安全恢复点。
     *
     * @param task       当前任务状态（含步骤与已采集证据）
     * @param checkpoint 本次要记录的恢复点
     */
    void save(TaskView task, AgentCheckpoint checkpoint);

    /** 某个任务最新的安全恢复点；没有任何恢复点时为空。 */
    Optional<AgentCheckpoint> latest(String taskId);

    /** 某个任务的全部恢复点，按序号递增。 */
    List<AgentCheckpoint> byTask(String taskId);

    /** 任务被回收时一并丢掉它的恢复点；返回删除条数（落库实现同时受外键级联保护）。 */
    int removeByTask(String taskId);
}
