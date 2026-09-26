package com.rover.agent.core.repository;

import com.rover.agent.core.model.Incident;
import java.util.List;
import java.util.Optional;

/**
 * 事件存储契约。
 *
 * 事件是连续追问的聚合点，因此除按 ID 查询外还要能按会话取回：一次追问失败需要整组回滚。
 * 当前由内存实现承载。
 */
public interface IncidentRepository {

    /** 保存事件；同 ID 覆盖。 */
    void save(Incident incident);

    /** 按 ID 查询事件。 */
    Optional<Incident> find(String incidentId);

    /** 全部事件，按写入顺序（最早写入在前）。 */
    List<Incident> listAll();

    /** 会话内的事件，按写入顺序（最早在前）。 */
    List<Incident> bySession(String sessionId);

    /** 删除事件。 */
    void remove(String incidentId);
}