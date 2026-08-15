package com.rover.nameserver.core.event.model;

import com.rover.common.event.Event;
import com.rover.common.protocol.AckMode;
import io.netty.channel.Channel;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Author: Daylight
 * Created: 2026-08-12 10:00:00
 * Description: Nameserver 协议事件基类，携带连接、请求 ID 与回包上下文
 */
@Data
@EqualsAndHashCode(callSuper = false)
public abstract class NameserverChannelEvent extends Event {

    private Channel channel;
    private long requestId;
    private boolean oneway;
    /** 协商后的 ACK 模式；部分事件用得到 */
    private AckMode ackMode;
}
