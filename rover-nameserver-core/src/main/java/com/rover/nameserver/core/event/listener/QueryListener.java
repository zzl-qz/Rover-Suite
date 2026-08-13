package com.rover.nameserver.core.event.listener;

import com.rover.common.event.EventListener;
import com.rover.common.model.ServiceInstance;
import com.rover.common.protocol.CommonResponseBody;
import com.rover.common.protocol.QueryRequest;
import com.rover.common.protocol.QueryResponseBody;
import com.rover.common.codec.ProtostuffSerializer;
import com.rover.nameserver.core.event.model.QueryEvent;
import com.rover.nameserver.core.event.support.NameserverChannelSupport;
import com.rover.nameserver.core.event.support.NameserverServices;
import com.rover.nameserver.core.event.support.NameserverTrace;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/** 处理查询：按服务/组取实例列表回包 */
@Slf4j
public class QueryListener implements EventListener<QueryEvent> {

    private final NameserverServices services;

    public QueryListener(NameserverServices services) {
        this.services = services;
    }

    @Override
    public void onEvent(QueryEvent event) {
        QueryRequest request = event.getRequest();
        if (request.getServiceName() == null || request.getServiceName().isBlank()) {
            log.warn("{}", NameserverTrace.of(event, "query-bad-request"));
            NameserverChannelSupport.reply(
                    event.getChannel(),
                    event.getRequestId(),
                    event.isOneway(),
                    NameserverChannelSupport.badRequest("serviceName 不能为空"));
            return;
        }
        List<ServiceInstance> instances = services.getRegistry()
                .query(request.getServiceName(), request.getGroup(), request.isHealthyOnly());
        QueryResponseBody queryBody = new QueryResponseBody();
        queryBody.setInstances(instances);
        queryBody.setRevision(services.getRegistry().revisionOf(request.getServiceName()));

        CommonResponseBody body = CommonResponseBody.success(ProtostuffSerializer.serialize(queryBody));
        body.setRevision(queryBody.getRevision());
        NameserverChannelSupport.fillNode(services.getOptions(), body);
        NameserverChannelSupport.reply(
                event.getChannel(), event.getRequestId(), event.isOneway(), body);
        log.debug("{}, instanceCount={}, revision={}",
                NameserverTrace.withService(event, "query-ok", request.getServiceName()),
                instances.size(),
                queryBody.getRevision());
    }
}
