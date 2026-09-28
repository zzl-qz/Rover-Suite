package com.rover.agent.core.model;

/**
 * 让用户点名一场旧对话的卡片。
 *
 * {@code label} 是给人看的标题，{@code value} 是会话 ID。点下去只按这个 ID 取那一场的结论，不扫描聊天原文。
 */
public record RecallChoice(String label, String value) {
}
