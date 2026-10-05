package com.rover.agent.core.event;

import com.rover.agent.core.model.TaskView;

/**
 * 任务全量快照；coveredEventId 及之前的事件已被覆盖，订阅者应跳过。
 *
 * @param task           任务视图（状态、步骤、结论、错误、澄清提问）
 * @param analysis       已产生的模型解读全文；尚无解读时为空串
 * @param coveredEventId 快照已覆盖到的事件序号；尚未发布任何事件时为 0
 * @param thinking       已产生的模型思考全文；当前模型不产出思考内容时为空串
 */
public record TaskSnapshot(TaskView task, String analysis, long coveredEventId, String thinking) { }