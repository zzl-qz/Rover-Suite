package com.rover.nameserver.core.event.model;

import com.rover.common.protocol.UnregisterRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Author: Daylight
 * Created: 2026-08-11 15:40:00
 * Description: 服务注销协议事件
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class UnregisterEvent extends NameserverChannelEvent {

    private UnregisterRequest request;
}
