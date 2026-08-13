package com.rover.nameserver.core.event.model;

import com.rover.common.protocol.HeartbeatRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 心跳协议事件 */
@Data
@EqualsAndHashCode(callSuper = true)
public class HeartbeatEvent extends NameserverChannelEvent {

    private HeartbeatRequest request;
}
