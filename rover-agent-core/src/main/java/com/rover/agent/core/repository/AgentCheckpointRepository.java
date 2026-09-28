package com.rover.agent.core.repository;

import com.rover.agent.core.model.AgentCheckpoint;
import com.rover.agent.core.model.TaskView;
import java.util.List;
import java.util.Optional;

/**
 * 安全恢复点存储契约：记录「这次调查可以安全地接着跑到哪里」。
 *
 * <p>写入不是孤立的一行插入，而是<b>推进一条任务的高水位</b>：任务状态、步骤、证据与恢复点必须在同一次写入里落下，
 * 否则会出现「恢复点说证据 3 条，库里其实只有 2 条」这种恢复时才暴露的坏状态。实现负责保证这一点，
 * 落库的那批证据就是 {@link TaskView#evidence()}——任务跑到一半时结论还没合成，但工具已经落过证据了，
 * 那正是最需要记录恢复点的时刻。
 */
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
