package com.rover.nameserver.core.event.listener;

import com.rover.common.event.EventListener;
import com.rover.common.protocol.CommonResponseBody;
import com.rover.common.protocol.UnsubscribeRequest;
import com.rover.nameserver.core.event.model.UnsubscribeEvent;
import com.rover.nameserver.core.event.support.NameserverChannelSupport;
import com.rover.nameserver.core.event.support.NameserverServices;
import com.rover.nameserver.core.event.support.NameserverTrace;
import lombok.extern.slf4j.Slf4j;

/**
 * Author: Daylight
 * Created: 2026-08-07 16:20:00
 * Description: 处理退订请求：移除订阅关系并回包
 */
@Slf4j
public class UnsubscribeListener implements EventListener<UnsubscribeEvent> {

    private final NameserverServices services;

    public UnsubscribeListener(NameserverServices services) {
        this.services = services;
    }

    @Override
    public void onEvent(UnsubscribeEvent event) {
        UnsubscribeRequest request = event.getRequest();
        if (request.getServiceName() == null || request.getServiceName().isBlank()) {
            log.warn("{}", NameserverTrace.of(event, "unsubscribe-bad-request"));
            NameserverChannelSupport.reply(
                    event.getChannel(),
                    event.getRequestId(),
                    event.isOneway(),
                    NameserverChannelSupport.badRequest("serviceName 不能为空"));
            return;
        }
        services.getSubscriptionManager()
                .unsubscribe(request.getServiceName(), request.getGroup(), event.getChannel());
        services.getMetrics().unsubscribe(request.getServiceName());
        CommonResponseBody body = CommonResponseBody.success();
        NameserverChannelSupport.fillNode(services.getOptions(), body);
        NameserverChannelSupport.reply(
                event.getChannel(), event.getRequestId(), event.isOneway(), body);
        log.info("{}", NameserverTrace.withService(event, "unsubscribe-ok", request.getServiceName()));
    }
}
