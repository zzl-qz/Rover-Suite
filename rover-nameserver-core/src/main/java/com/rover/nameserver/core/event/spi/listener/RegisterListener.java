package com.rover.nameserver.core.event.spi.listener;

import com.rover.common.event.EventListener;
import com.rover.common.protocol.AckMode;
import com.rover.common.protocol.CommonResponseBody;
import com.rover.common.protocol.RegisterRequest;
import com.rover.nameserver.core.event.model.RegisterEvent;
import com.rover.nameserver.core.event.support.NameserverChannelSupport;
import com.rover.nameserver.core.event.support.NameserverServices;
import com.rover.nameserver.core.event.support.NameserverTrace;
import com.rover.nameserver.core.registry.RegistrySnapshot;
import lombok.extern.slf4j.Slf4j;

/**
 * Author: Daylight
 * Created: 2026-08-13 00:00:00
 * Description: 处理注册：写注册表 → 绑连接 → Push → 回包
 */
@Slf4j
public class RegisterListener implements EventListener<RegisterEvent> {

    private final NameserverServices services;

    public RegisterListener(NameserverServices services) {
        this.services = services;
    }

    @Override
    public void onEvent(RegisterEvent event) {
        RegisterRequest request = event.getRequest();
        if (!validRegister(request)) {
            log.warn("{}", NameserverTrace.of(event, "register-bad-request"));
            NameserverChannelSupport.reply(
                    event.getChannel(),
                    event.getRequestId(),
                    event.isOneway(),
                    NameserverChannelSupport.badRequest("注册参数不完整"));
            return;
        }

        RegistrySnapshot snapshot = services.getRegistry().register(request);
        NameserverChannelSupport.bindInstance(
                event.getChannel(), request.getServiceName(), request.getInstanceId());
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
        log.info("{}, revision={}",
                NameserverTrace.withServiceInstance(
                        event, "register-ok", request.getServiceName(), request.getInstanceId()),
                snapshot.getRevision());
    }

    private static boolean validRegister(RegisterRequest request) {
        return request != null
                && request.getServiceName() != null && !request.getServiceName().isBlank()
                && request.getHost() != null && !request.getHost().isBlank()
                && request.getInstanceId() != null && !request.getInstanceId().isBlank()
                && request.getPort() > 0;
    }
}
