package com.rover.nameserver.core.event.model;

import com.rover.common.protocol.UnsubscribeRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 退订协议事件 */
@Data
@EqualsAndHashCode(callSuper = true)
public class UnsubscribeEvent extends NameserverChannelEvent {

    private UnsubscribeRequest request;
}
