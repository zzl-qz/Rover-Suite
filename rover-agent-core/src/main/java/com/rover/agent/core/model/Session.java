package com.rover.agent.core.model;

import java.util.List;

/**
 * 一段连续交互的会话：承载上下文，使后续调查可以继承前一次的目标与结论。
 *
 * 会话按用户归档：{@code userId} 只允许来自后端认证上下文，不接受前端提交。
 * {@code activeIncidentId} 指向当前正在调查的事件，是连续追问的锚点；尚无事件时为 {@code null}。
 *
 * @param sessionId          会话 ID
 * @param userId             归属用户；无认证上下文时为空
 * @param title              会话标题；由首次提问推导，尚未生成时为空
 * @param activeIncidentId   当前事件 ID；无当前事件时为 {@code null}
 * @param status             会话状态
 * @param createdAtMillis    创建时刻
 * @param lastActiveAtMillis 最近活动时刻
 * @param incidentIds        会话内的事件
 */
public record Session(String sessionId, String userId, String title, String activeIncidentId, SessionStatus status,
                      long createdAtMillis, long lastActiveAtMillis, List<String> incidentIds) { }