package com.rover.nameserver.core.event.model;

import com.rover.common.protocol.SubscribeRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Author: Daylight
 * Created: 2026-08-12 14:30:00
 * Description: 订阅协议事件
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class SubscribeEvent extends NameserverChannelEvent {

    private SubscribeRequest request;
}
