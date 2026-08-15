package com.rover.nameserver.core.event.model;

import com.rover.common.protocol.RegisterRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Author: Daylight
 * Created: 2026-08-11 09:15:00
 * Description: 服务注册协议事件
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class RegisterEvent extends NameserverChannelEvent {

    private RegisterRequest request;
}
