package com.rover.nameserver.core.event.model;

import com.rover.common.protocol.QueryRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 实例查询协议事件 */
@Data
@EqualsAndHashCode(callSuper = true)
public class QueryEvent extends NameserverChannelEvent {

    private QueryRequest request;
}
