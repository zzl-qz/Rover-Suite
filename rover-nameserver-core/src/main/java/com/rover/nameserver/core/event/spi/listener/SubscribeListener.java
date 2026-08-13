package com.rover.nameserver.core.event.spi.listener;

import com.rover.common.event.EventListener;
import com.rover.common.protocol.CommonResponseBody;
import com.rover.common.protocol.SubscribeRequest;
import com.rover.nameserver.core.event.model.SubscribeEvent;
import com.rover.nameserver.core.event.support.NameserverChannelSupport;
import com.rover.nameserver.core.event.support.NameserverServices;
import com.rover.nameserver.core.registry.RegistrySnapshot;

/** 处理订阅：登记订阅 → 立刻推当前全量 → 回包 */
public class SubscribeListener implements EventListener<SubscribeEvent> {

    private final NameserverServices services;

    public SubscribeListener(NameserverServices services) {
        this.services = services;
    }

    @Override
    public void onEvent(SubscribeEvent event) {
        SubscribeRequest request = event.getRequest();
        if (request.getServiceName() == null || request.getServiceName().isBlank()) {
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
        NameserverChannelSupport.reply(
                event.getChannel(), event.getRequestId(), event.isOneway(), body);
    }
}
