package com.rover.nameserver.core.event.listener;

import com.rover.common.event.EventListener;
import com.rover.common.protocol.AckMode;
import com.rover.common.protocol.CommonResponseBody;
import com.rover.common.protocol.RegisterRequest;
import com.rover.nameserver.core.event.model.RegisterEvent;
import com.rover.nameserver.core.event.support.NameserverChannelSupport;
import com.rover.nameserver.core.event.support.NameserverServices;
import com.rover.nameserver.core.event.support.NameserverTrace;
import com.rover.nameserver.core.registration.RegistrationOwner;
import com.rover.nameserver.core.registration.RegistrationResult;
import lombok.extern.slf4j.Slf4j;

/**
 * Author: Daylight
 * Created: 2026-08-02 09:40:00
 * Description: 处理注册请求：写入注册表、绑定连接、触发推送并回包
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

        RegistrationOwner owner = NameserverChannelSupport.registrationOwner(event.getChannel());
        RegistrationResult result = services.getRegistrationService().register(request, owner);
        // 临时实例归连接所有，断线自动清理；persistent 独立于连接，只能显式注销或由健康检查标不健康。
        if (request.isEphemeral()) {
            NameserverChannelSupport.bindInstance(
                    event.getChannel(), request.getServiceName(), request.getInstanceId(), owner);
        } else {
            // 同一 owner 把临时实例改为持久实例时，必须同步解除 channel 绑定。
            NameserverChannelSupport.unbindInstance(
                    event.getChannel(), request.getServiceName(), request.getInstanceId(), owner);
        }
        // 断线事件可能已经跑过（当时还没 bind）。仅临时实例需要在连接已死时回滚注册。
        if (request.isEphemeral()
                && event.getChannel() != null
                && !event.getChannel().isActive()) {
            services.getRegistrationService()
                    .unregister(request.getServiceName(), request.getInstanceId(), owner);
            NameserverChannelSupport.unbindInstance(
                    event.getChannel(), request.getServiceName(), request.getInstanceId(), owner);
            log.warn("{}, reason=channel-inactive-after-register",
                    NameserverTrace.withServiceInstance(
                            event, "register-aborted", request.getServiceName(), request.getInstanceId()));
            return;
        }
        AckMode ackMode = event.getAckMode();
        CommonResponseBody body = CommonResponseBody.success()
                .withAck(ackMode, services.getWriteAckPolicy().requiredAcks(
                        ackMode,
                        services.getOptions().getReplicationFactor(),
                        services.getOptions().isClusterEnabled()));
        body.setRevision(result.revision());
        NameserverChannelSupport.fillNode(services.getOptions(), body);
        NameserverChannelSupport.fillGeneration(services, body);
        NameserverChannelSupport.reply(
                event.getChannel(), event.getRequestId(), event.isOneway(), body);
        log.info("{}, revision={}, epoch={}",
                NameserverTrace.withServiceInstance(
                        event, "register-ok", request.getServiceName(), request.getInstanceId()),
                result.revision(),
                services.getEpoch());
    }

    private static boolean validRegister(RegisterRequest request) {
        return request != null
                && request.getServiceName() != null && !request.getServiceName().isBlank()
                && request.getHost() != null && !request.getHost().isBlank()
                && request.getInstanceId() != null && !request.getInstanceId().isBlank()
                && request.getPort() > 0;
    }
}
