package com.rover.nameserver.core.event.model;

import com.rover.common.protocol.HeartbeatRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Author: Daylight
 * Created: 2026-08-10 11:05:00
 * Description: 心跳协议事件
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class HeartbeatEvent extends NameserverChannelEvent {

    private HeartbeatRequest request;
}
