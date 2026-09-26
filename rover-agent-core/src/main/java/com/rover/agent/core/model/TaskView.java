package com.rover.agent.core.model;

import java.util.List;

/**
 * 调查任务对外视图：Admin 轮询与任务记录都使用这个对象。
 *
 * {@code currentStage} 是当前（或最近一个）阶段的类型，供前端把进度归位到固定阶段；
 * {@code path} 保留为被调查的请求路径（兼容既有页面与旧接口），{@code target} 是结构化的调查对象。
 *
 * @param taskId            任务 ID
 * @param sessionId         任务所属会话
 * @param incidentId        任务所属事件；目标解析完成前可能为 {@code null}，由执行线程回填
 * @param status            任务状态
 * @param currentStage      当前阶段；尚未开始任何步骤时为 {@code null}
 * @param path              被调查的请求路径；目标解析完成前为空串
 * @param target            被调查的资源对象；目标解析完成前为未知对象
 * @param question          用户问题
 * @param createdAtMillis   创建时刻
 * @param completedAtMillis 结束时刻；未结束时为 0
 * @param steps             步骤记录
 * @param result            结论报告；未完成时为 null
 * @param error             失败说明；成功时为 null
 * @param clarification     等待用户补充信息时的澄清提问；其他状态为 null
 */
public record TaskView(String taskId, String sessionId, String incidentId, TaskStatus status,
                       AgentStepType currentStage, String path, ResourceTarget target, String question,
                       long createdAtMillis, long completedAtMillis, List<Step> steps,
                       InvestigationReport result, String error, String clarification) { }