package com.rover.agent.core.repository;

import com.rover.agent.core.model.TaskView;
import java.util.List;
import java.util.Optional;

/**
 * 调查任务存储契约。
 *
 * 存的是任务快照（{@link TaskView}）：每次状态变更都覆盖写入同一条记录，因此这里就是任务记录的唯一真相来源。
 * 进程内的执行态（线程池、解读增量订阅者）不在这里，属于运行层的执行细节。
 */
public interface AgentTaskRepository {

    /** 保存任务快照；同 ID 覆盖。 */
    void save(TaskView task);

    /** 按 ID 查询任务快照。 */
    Optional<TaskView> find(String taskId);

    /** 全部任务快照，按写入顺序（最早写入在前）。 */
    List<TaskView> listAll();

    /** 删除任务快照。 */
    void remove(String taskId);
}