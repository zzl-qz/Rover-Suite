package com.rover.agent.core.model;

import java.util.List;

/**
 * 一个被调查的故障单元：同一事件可以承载多次调查任务，用于连续追问与处置后复核。
 *
 * @param incidentId      事件 ID
 * @param sessionId       所属会话
 * @param origin          调查来源
 * @param targetPath      故障对象：被调查的请求路径
 * @param createdAtMillis 创建时刻
 * @param taskIds         该事件下的调查任务
 */
public record Incident(String incidentId, String sessionId, IncidentOrigin origin, String targetPath,
                       long createdAtMillis, List<String> taskIds) { }