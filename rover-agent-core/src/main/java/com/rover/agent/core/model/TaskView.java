package com.rover.agent.core.model;

import java.util.List;

/**
 * 调查任务对外视图：Admin 诊断页轮询的就是这个对象。
 *
 * @param taskId            任务 ID
 * @param sessionId         任务所属会话
 * @param incidentId        任务所属事件
 * @param status            任务状态
 * @param path              被调查的请求路径
 * @param question          用户问题
 * @param createdAtMillis   创建时刻
 * @param completedAtMillis 结束时刻；未结束时为 0
 * @param steps             步骤记录
 * @param result            结论报告；未完成时为 null
 * @param error             失败说明；成功时为 null
 */
public record TaskView(String taskId, String sessionId, String incidentId, TaskStatus status, String path,
                       String question, long createdAtMillis, long completedAtMillis, List<Step> steps,
                       InvestigationReport result, String error) { }