package com.rover.nameserver.core.event.listener;

import com.rover.common.constants.StatusConstants;
import com.rover.common.event.EventListener;
import com.rover.common.protocol.CommonResponseBody;
import com.rover.common.protocol.HeartbeatRequest;
import com.rover.nameserver.core.event.model.HeartbeatEvent;
import com.rover.nameserver.core.event.support.NameserverChannelSupport;
import com.rover.nameserver.core.event.support.NameserverServices;
import com.rover.nameserver.core.event.support.NameserverTrace;
import lombok.extern.slf4j.Slf4j;

/** 处理心跳：刷新 lastHeartbeat → 回 revision */
@Slf4j
public class HeartbeatListener implements EventListener<HeartbeatEvent> {

    private final NameserverServices services;

    public HeartbeatListener(NameserverServices services) {
        this.services = services;
    }

    @Override
    public void onEvent(HeartbeatEvent event) {
        HeartbeatRequest request = event.getRequest();
        if (request.getServiceName() == null || request.getInstanceId() == null
                || request.getServiceName().isBlank() || request.getInstanceId().isBlank()) {
            log.warn("{}", NameserverTrace.of(event, "heartbeat-bad-request"));
            NameserverChannelSupport.reply(
                    event.getChannel(),
                    event.getRequestId(),
                    event.isOneway(),
                    NameserverChannelSupport.badRequest("心跳参数不完整"));
            return;
        }
        boolean ok = services.getRegistry().heartbeat(request.getServiceName(), request.getInstanceId());
        if (!ok) {
            log.warn("{}", NameserverTrace.withServiceInstance(
                    event, "heartbeat-not-found", request.getServiceName(), request.getInstanceId()));
            NameserverChannelSupport.replyFail(
                    event.getChannel(),
                    event.getRequestId(),
                    event.isOneway(),
                    StatusConstants.SERVICE_NOT_FOUND,
                    "实例不存在，请先注册");
            return;
        }
        CommonResponseBody body = CommonResponseBody.success();
        body.setRevision(services.getRegistry().revisionOf(request.getServiceName()));
        NameserverChannelSupport.fillNode(services.getOptions(), body);
        NameserverChannelSupport.fillGeneration(services, body);
        NameserverChannelSupport.reply(
                event.getChannel(), event.getRequestId(), event.isOneway(), body);
        log.debug("{}", NameserverTrace.withServiceInstance(
                event, "heartbeat-ok", request.getServiceName(), request.getInstanceId()));
    }
}
