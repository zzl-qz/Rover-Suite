package com.rover.agent.core.model;

/**
 * 一次调查中的一个步骤记录。
 *
 * {@code type} 是机器可读的阶段类型，{@code name} 是该步骤的展示名（中文，允许随文案调整）；
 * {@code inputSummary} 只在该步上报了「要做什么」时才有值，{@code outputSummary} 在该步产出结果后才有值；
 * 失败的步骤把原因放在 {@code error}，此时没有 {@code outputSummary}。
 *
 * @param stepId          步骤 ID
 * @param taskId          所属任务
 * @param type            阶段类型
 * @param name            展示名
 * @param status          步骤状态
 * @param inputSummary    输入说明；未上报时为 {@code null}
 * @param outputSummary   结果说明；未产出时为 {@code null}
 * @param startedAtMillis 开始时刻
 * @param finishedAtMillis 结束时刻；未结束时为 0
 * @param error           失败原因；成功时为 {@code null}
 */
public record Step(String stepId, String taskId, AgentStepType type, String name, StepStatus status,
                   String inputSummary, String outputSummary, long startedAtMillis, long finishedAtMillis,
                   String error) { }