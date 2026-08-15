package com.rover.nameserver.core.event.model;

import io.netty.channel.Channel;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Author: Daylight
 * Created: 2026-08-09 09:30:00
 * Description: 连接断开事件，用于清理订阅与该连接绑定的实例
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ChannelInactiveEvent extends NameserverChannelEvent {

    public static ChannelInactiveEvent of(Channel channel) {
        ChannelInactiveEvent event = new ChannelInactiveEvent();
        event.setChannel(channel);
        return event;
    }
}
