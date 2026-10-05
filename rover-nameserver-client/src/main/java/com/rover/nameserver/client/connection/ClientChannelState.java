package com.rover.nameserver.client.connection;

import io.netty.channel.Channel;
import java.util.Objects;

/**
 * 在同一锁下管理客户端连接，拒绝失活或关闭后晚到的连接。
 * 旧 channel 的延迟关闭事件不清除新连接。
 */
final class ClientChannelState {

    /** 当前允许业务请求使用的连接，仅能通过 activate/deactivate 修改。 */
    private Channel current;
    /** start 后允许发布连接，shutdown 后拒绝晚到的连接成功结果。 */
    private boolean acceptingConnections;

    synchronized void startAcceptingConnections() {
        acceptingConnections = true;
    }

    /**
     * 原子发布一个连接。候选连接必须仍然 active；若已有其他活连接则保留现有连接。
     */
    synchronized boolean activate(Channel candidate) {
        Objects.requireNonNull(candidate, "candidate");
        if (!acceptingConnections || !candidate.isActive()) {
            return false;
        }
        if (current != null && current != candidate && current.isActive()) {
            return false;
        }
        current = candidate;
        return true;
    }

    /**
     * 仅当断开的正是当前连接时才摘除；cleanup 与摘除在同一临界区执行，避免新连接
     * 已发布后，旧连接再批量失败新连接的在途请求。
     */
    synchronized boolean deactivate(Channel candidate, Runnable cleanup) {
        Objects.requireNonNull(candidate, "candidate");
        if (current != candidate) {
            return false;
        }
        current = null;
        if (cleanup != null) {
            cleanup.run();
        }
        return true;
    }

    synchronized Channel current() {
        return current;
    }

    boolean isActive() {
        Channel snapshot = current();
        return snapshot != null && snapshot.isActive();
    }

    /** 停止接收连接并原子摘除当前连接，返回值由调用方负责关闭。 */
    synchronized Channel stopAcceptingConnections() {
        acceptingConnections = false;
        Channel previous = current;
        current = null;
        return previous;
    }
}
