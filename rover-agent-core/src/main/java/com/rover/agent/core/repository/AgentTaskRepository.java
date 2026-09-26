package com.rover.agent.core.repository;

import com.rover.agent.core.model.TaskView;
import java.util.List;
import java.util.Optional;

/**
 * 调查任务存储契约。
 *
 * 存的是任务快照（{@link TaskView}）：每次状态变更都覆盖写入同一条记录，因此这里就是任务记录的唯一真相来源。
 * 进程内的执行态（线程池、解读增量订阅者）不在这里，属于运行层的执行细节。
 *
 * 查询按业务语义给出（按会话、按事件、按执行中状态），调用方不再用 {@code listAll().stream().filter(...)}
 * 做全量扫描：接入持久化后这些方法是可下推到 SQL 的条件，全表扫描则不是。
 * 容量满时内存实现拒绝写入（抛 {@code IllegalStateException}），腾出位置由登记表淘汰最早的已结束任务完成。
 */
public interface AgentTaskRepository {

    /** 保存任务快照；同 ID 覆盖。 */
    void save(TaskView task);

    /** 按 ID 查询任务快照。 */
    Optional<TaskView> find(String taskId);

    /**
     * 会话内仍在执行的任务（PENDING / RUNNING），按创建顺序。
     *
     * 用于「同一会话同时最多一个执行中的任务」的并发约束；WAITING_INPUT 是等待用户输入的静止态，
     * 不属于执行中，因此不影响用户继续提问。
     */
    Optional<TaskView> findActiveBySessionId(String sessionId);

    /** 全部任务快照，按写入顺序（最早写入在前）。 */
    List<TaskView> listAll();

    /** 会话内的全部任务快照，按写入顺序（最早在前）；用于会话级联清理时逐个回收。 */
    List<TaskView> findBySessionId(String sessionId);

    /** 事件下的全部任务快照，按写入顺序（最早在前）；目标解析完成前的任务不属于任何事件。 */
    List<TaskView> findByIncidentId(String incidentId);

    /**
     * 会话内最近的任务快照，按创建时间倒序（最新的在前），最多 {@code limit} 条。
     *
     * Workbench 聚合视图靠它一次取齐最近任务，不必先按会话全量扫描再截断。
     */
    List<TaskView> recentBySession(String sessionId, int limit);

    /** 删除任务快照。 */
    void remove(String taskId);

    /** 删除会话内的全部任务快照，返回删除条数（会话级联清理用）。 */
    int removeBySession(String sessionId);

    /** 删除事件下的全部任务快照，返回删除条数（事件级联清理用）。 */
    int removeByIncident(String incidentId);
}