package com.rover.agent.core.event;

import com.rover.agent.core.model.TaskView;

/**
 * 任务全量快照：晚连上或掉队重连的订阅者靠它一次对齐，不必回放历史事件。
 *
 * {@code coveredEventId} 是快照读取时任务已发布的最新事件序号（任务锁内读取，因此快照内容
 * 必然包含该序号及其之前所有事件的效果）。订阅者据此丢弃重复事件：本地已收到的事件序号
 * 小于等于该值就跳过，避免「补发快照 + 继续推增量」时同一段文本被追加两次。
 *
 * @param task           任务视图（状态、步骤、结论、错误、澄清提问）
 * @param analysis       已产生的模型解读全文；尚无解读时为空串
 * @param coveredEventId 快照已覆盖到的事件序号；尚未发布任何事件时为 0
 */
public record TaskSnapshot(TaskView task, String analysis, long coveredEventId) { }