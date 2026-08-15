package com.rover.nameserver.core.event.support;

import com.rover.common.constants.ProtocolTypeNames;
import com.rover.nameserver.core.event.model.NameserverChannelEvent;
import io.netty.channel.Channel;

/**
 * Author: Daylight
 * Created: 2026-08-05 16:30:00
 * Description: 排障日志关联字段拼装（requestId / remote / 协议名），异步网络下靠关联 ID 串联请求
 */
public final class NameserverTrace {

    private NameserverTrace() {
    }

    public static String remote(Channel channel) {
        return channel == null || channel.remoteAddress() == null
                ? "unknown"
                : channel.remoteAddress().toString();
    }

    public static String of(NameserverChannelEvent event, String action) {
        return "action=" + action
                + ", requestId=" + (event == null ? -1 : event.getRequestId())
                + ", remote=" + remote(event == null ? null : event.getChannel());
    }

    public static String of(Channel channel, long requestId, byte type, String action) {
        return "action=" + action
                + ", type=" + ProtocolTypeNames.nameOf(type)
                + ", requestId=" + requestId
                + ", remote=" + remote(channel);
    }

    public static String withService(NameserverChannelEvent event, String action, String serviceName) {
        return of(event, action) + ", serviceName=" + nullToDash(serviceName);
    }

    public static String withServiceInstance(
            NameserverChannelEvent event, String action, String serviceName, String instanceId) {
        return withService(event, action, serviceName) + ", instanceId=" + nullToDash(instanceId);
    }

    private static String nullToDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
