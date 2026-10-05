package com.rover.agent.core.port;

import java.util.List;

/**
 * 候选路由表通过校验后的差异预览，不提交变更。
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
