package com.rover.nameserver.core.event.model;

import com.rover.common.protocol.RegisterRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 服务注册协议事件 */
@Data
@EqualsAndHashCode(callSuper = true)
public class RegisterEvent extends NameserverChannelEvent {

    private RegisterRequest request;
}
