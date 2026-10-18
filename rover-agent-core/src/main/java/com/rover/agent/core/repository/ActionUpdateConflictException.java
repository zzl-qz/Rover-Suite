package com.rover.agent.core.repository;

/** 变更记录已被其他请求更新或删除；调用方必须重新读取，不能覆盖新状态。 */
public final class ActionUpdateConflictException extends RuntimeException {

    public ActionUpdateConflictException(String actionId) {
        super("变更记录已更新或删除，请重新读取：" + actionId);
    }
}
