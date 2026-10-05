package com.rover.agent.core.repository;

import com.rover.agent.core.model.Incident;
import java.util.List;
import java.util.Optional;

/** 按 ID 和会话存储与查询事件；内存容量满时拒绝新增，由上层整组清理。 */
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

    /** 删除会话内的全部事件，返回删除条数（会话级联清理用）。 */
    int removeBySession(String sessionId);
}