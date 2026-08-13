package com.rover.nameserver.core.event.model;

import com.rover.common.protocol.SubscribeRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 订阅协议事件 */
@Data
@EqualsAndHashCode(callSuper = true)
public class SubscribeEvent extends NameserverChannelEvent {

    private SubscribeRequest request;
}
