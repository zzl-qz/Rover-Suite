package com.rover.nameserver.core.event.listener;

import com.rover.common.event.EventListener;
import com.rover.common.protocol.CommonResponseBody;
import com.rover.common.protocol.SubscribeRequest;
import com.rover.nameserver.core.event.model.SubscribeEvent;
import com.rover.nameserver.core.event.support.NameserverChannelSupport;
import com.rover.nameserver.core.event.support.NameserverServices;
import com.rover.nameserver.core.event.support.NameserverTrace;
import com.rover.nameserver.core.registry.RegistrySnapshot;
import lombok.extern.slf4j.Slf4j;

/**
 * Author: Daylight
 * Created: 2026-08-04 15:45:00
 * Description: 处理订阅请求：登记订阅、立即推送当前全量快照并回包
 */
@Slf4j
public class SubscribeListener implements EventListener<SubscribeEvent> {

    private final NameserverServices services;

    public SubscribeListener(NameserverServices services) {
        this.services = services;
    }

    @Override
    public void onEvent(SubscribeEvent event) {
        SubscribeRequest request = event.getRequest();
        if (request.getServiceName() == null || request.getServiceName().isBlank()) {
            log.warn("{}", NameserverTrace.of(event, "subscribe-bad-request"));
            NameserverChannelSupport.reply(
                    event.getChannel(),
                    event.getRequestId(),
                    event.isOneway(),
                    NameserverChannelSupport.badRequest("serviceName 不能为空"));
            return;
        }
        services.getSubscriptionManager()
                .subscribe(request.getServiceName(), request.getGroup(), event.getChannel());

        RegistrySnapshot snapshot = RegistrySnapshot.of(
                request.getServiceName(),
                request.getGroup(),
                services.getRegistry().revisionOf(request.getServiceName()),
                services.getRegistry().query(request.getServiceName(), request.getGroup(), false));
        services.getPushService().pushSnapshot(snapshot);

        CommonResponseBody body = CommonResponseBody.success();
        body.setRevision(snapshot.getRevision());
        NameserverChannelSupport.fillNode(services.getOptions(), body);
        NameserverChannelSupport.fillGeneration(services, body);
        NameserverChannelSupport.reply(
                event.getChannel(), event.getRequestId(), event.isOneway(), body);
        log.info("{}, revision={}, epoch={}",
                NameserverTrace.withService(event, "subscribe-ok", request.getServiceName()),
                snapshot.getRevision(),
                services.getEpoch());
    }
}
