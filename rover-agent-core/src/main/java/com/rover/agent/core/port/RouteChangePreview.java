package com.rover.agent.core.port;

import java.util.List;

/**
 * 一次候选变更的预览结果：网关按整表比对后给出的逐条差异，<b>没有落盘、没有生效</b>。
 *
 * <p>预览的意义是把「网关认不认这个候选表、会改动什么」在执行之前摊开：
 * 校验不通过会直接抛 {@link RouteControlException}，因此能走到这里就说明候选表本身是合法的。
 *
 * @param revision 预览依据的路由版本
 * @param message  网关给的说明
 * @param changes  逐条差异（人类可读的一行文本）
 */
public record RouteChangePreview(int revision, String message, List<String> changes) {

    public RouteChangePreview {
        message = message == null ? "" : message;
        changes = changes == null ? List.of() : List.copyOf(changes);
    }
}
