package com.rover.nameserver.core.event.model;

import com.rover.common.protocol.UnsubscribeRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Author: Daylight
 * Created: 2026-08-10 10:20:00
 * Description: 退订协议事件
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class UnsubscribeEvent extends NameserverChannelEvent {

    private UnsubscribeRequest request;
}
