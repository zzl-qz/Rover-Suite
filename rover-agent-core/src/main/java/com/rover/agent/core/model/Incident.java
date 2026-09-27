package com.rover.agent.core.model;

import java.util.List;

/**
 * 一个被调查的故障单元：同一事件可承载多次调查任务，用于连续追问与处置后复核。
 *
 * 连续追问沿用同一事件，只有换了明确的新对象才另开；{@code summary} 是最新结论摘要，由编排层回写。
 */
public record Incident(String incidentId, String sessionId, IncidentOrigin origin, String title,
                       IncidentStatus status, ResourceTarget target,
                       TimeRange timeRange, String summary, long createdAtMillis, long updatedAtMillis,
                       List<String> taskIds) { }