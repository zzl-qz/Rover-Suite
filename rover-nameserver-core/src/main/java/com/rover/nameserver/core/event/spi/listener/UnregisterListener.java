package com.rover.nameserver.core.event.spi.listener;

import com.rover.common.constants.StatusConstants;
import com.rover.common.event.EventListener;
import com.rover.common.protocol.AckMode;
import com.rover.common.protocol.CommonResponseBody;
import com.rover.common.protocol.UnregisterRequest;
import com.rover.nameserver.core.event.model.UnregisterEvent;
import com.rover.nameserver.core.event.support.NameserverChannelSupport;
import com.rover.nameserver.core.event.support.NameserverServices;
import com.rover.nameserver.core.registry.RegistrySnapshot;

/** 处理注销：摘注册表 → 解绑 → Push → 回包 */
public class UnregisterListener implements EventListener<UnregisterEvent> {

    private final NameserverServices services;

    public UnregisterListener(NameserverServices services) {
        this.services = services;
    }

    @Override
    public void onEvent(UnregisterEvent event) {
        UnregisterRequest request = event.getRequest();
        if (request.getServiceName() == null || request.getServiceName().isBlank()
                || request.getInstanceId() == null || request.getInstanceId().isBlank()) {
            NameserverChannelSupport.reply(
                    event.getChannel(),
                    event.getRequestId(),
                    event.isOneway(),
                    NameserverChannelSupport.badRequest("注销参数不完整"));
            return;
        }

        RegistrySnapshot snapshot = services.getRegistry()
                .unregister(request.getServiceName(), request.getInstanceId());
        NameserverChannelSupport.unbindInstance(
                event.getChannel(), request.getServiceName(), request.getInstanceId());
        if (snapshot == null) {
            NameserverChannelSupport.replyFail(
                    event.getChannel(),
                    event.getRequestId(),
                    event.isOneway(),
                    StatusConstants.SERVICE_NOT_FOUND,
                    "实例不存在");
            return;
        }
        services.getPushService().pushSnapshot(snapshot);

        AckMode ackMode = event.getAckMode();
        CommonResponseBody body = CommonResponseBody.success()
                .withAck(ackMode, services.getWriteAckPolicy().requiredAcks(
                        ackMode,
                        services.getOptions().getReplicationFactor(),
                        services.getOptions().isClusterEnabled()));
        body.setRevision(snapshot.getRevision());
        NameserverChannelSupport.fillNode(services.getOptions(), body);
        NameserverChannelSupport.reply(
                event.getChannel(), event.getRequestId(), event.isOneway(), body);
    }
}
