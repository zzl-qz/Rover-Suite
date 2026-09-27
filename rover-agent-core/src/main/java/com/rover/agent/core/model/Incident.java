package com.rover.agent.core.model;

import java.util.List;

/**
 * 一个被调查的故障单元：同一事件可以承载多次调查任务，用于连续追问与处置后复核。
 *
 * {@code target} 是结构化的调查对象（路由 / 服务 / 实例），{@code title} 是它的展示名；
 * 连续追问时后续任务沿用同一个事件，只有用户换了一个明确的新对象才另开事件。
 * {@code summary} 是该事件当前最新的结论摘要，由负责编排的用例层在结论产出后回写。
 *
 * @param incidentId       事件 ID
 * @param sessionId        所属会话
 * @param origin           调查来源
 * @param title            展示名；默认取目标取值
 * @param status           事件状态
 * @param target           被调查的资源对象
 * @param timeRange        调查时间范围；用户未指定时为 {@link TimeRange#unspecified()}
 * @param summary          当前结论摘要；尚无结论时为空串
 * @param createdAtMillis  创建时刻
 * @param updatedAtMillis  最近更新时刻
 * @param taskIds          该事件下的调查任务
 */
public record Incident(String incidentId, String sessionId, IncidentOrigin origin, String title,
                       IncidentStatus status, ResourceTarget target,
                       TimeRange timeRange, String summary, long createdAtMillis, long updatedAtMillis,
                       List<String> taskIds) { }