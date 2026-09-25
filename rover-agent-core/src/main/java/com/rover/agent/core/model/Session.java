package com.rover.agent.core.model;

import java.util.List;

/**
 * 一段连续交互的会话：承载上下文，使后续调查可以继承前一次的目标与结论。
 *
 * 本轮只做到「一次主动提问开启一个会话」，会话续接（同一会话内追加调查任务）随连续追问能力一起实现。
 *
 * @param sessionId          会话 ID
 * @param createdAtMillis    创建时刻
 * @param lastActiveAtMillis 最近活动时刻
 * @param incidentIds        会话内的事件
 */
public record Session(String sessionId, long createdAtMillis, long lastActiveAtMillis, List<String> incidentIds) { }