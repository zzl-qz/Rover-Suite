package com.rover.nameserver.core.event.model;

import com.rover.common.protocol.UnregisterRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 服务注销协议事件 */
@Data
@EqualsAndHashCode(callSuper = true)
public class UnregisterEvent extends NameserverChannelEvent {

    private UnregisterRequest request;
}
