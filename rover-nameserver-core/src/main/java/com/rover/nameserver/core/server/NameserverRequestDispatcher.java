package com.rover.nameserver.core.server;

import com.rover.common.constants.ProtocolConstants;
import com.rover.common.constants.ProtocolTypeNames;
import com.rover.common.constants.StatusConstants;
import com.rover.common.event.EventBus;
import com.rover.common.protocol.CommonResponseBody;
import com.rover.common.protocol.HeartbeatRequest;
import com.rover.common.protocol.QueryRequest;
import com.rover.common.protocol.RegisterRequest;
import com.rover.common.protocol.RoverMessage;
import com.rover.common.protocol.SubscribeRequest;
import com.rover.common.protocol.UnregisterRequest;
import com.rover.common.protocol.UnsubscribeRequest;
import com.rover.common.codec.RoverMessageCodecSupport;
import com.rover.nameserver.core.event.model.ChannelInactiveEvent;
import com.rover.nameserver.core.event.model.HeartbeatEvent;
import com.rover.nameserver.core.event.model.NameserverChannelEvent;
import com.rover.nameserver.core.event.model.QueryEvent;
import com.rover.nameserver.core.event.model.RegisterEvent;
import com.rover.nameserver.core.event.model.SubscribeEvent;
import com.rover.nameserver.core.event.model.UnregisterEvent;
import com.rover.nameserver.core.event.model.UnsubscribeEvent;
import com.rover.nameserver.core.event.support.NameserverChannelSupport;
import com.rover.nameserver.core.event.support.NameserverServices;
import com.rover.nameserver.core.event.support.NameserverTrace;
import io.netty.channel.Channel;
import lombok.extern.slf4j.Slf4j;

/**
 * Author: Daylight
 * Created: 2026-08-08 17:50:00
 * Description: 协议消息入口映射器（薄入口）：解码并组装为 XxxEvent 发布到 EventBus，业务在 Listener 处理
 */
@Slf4j
public class NameserverRequestDispatcher {

    private final EventBus eventBus;
    private final NameserverServices services;

    public NameserverRequestDispatcher(EventBus eventBus, NameserverServices services) {
        this.eventBus = eventBus;
        this.services = services;
    }

    /** 按消息类型映射成事件并异步发布，未知类型或映射异常统一回错误包。 */
    public void dispatch(Channel channel, RoverMessage message) {
        if (message == null) {
            return;
        }
        try {
            switch (message.getType()) {
                case ProtocolConstants.REGISTER_REQUEST -> publishRegister(channel, message);
                case ProtocolConstants.UNREGISTER_REQUEST -> publishUnregister(channel, message);
                case ProtocolConstants.HEARTBEAT_REQUEST -> publishHeartbeat(channel, message);
                case ProtocolConstants.QUERY_REQUEST -> publishQuery(channel, message);
                case ProtocolConstants.SUBSCRIBE_REQUEST -> publishSubscribe(channel, message);
                case ProtocolConstants.UNSUBSCRIBE_REQUEST -> publishUnsubscribe(channel, message);
                default -> {
                    log.warn("{}", NameserverTrace.of(
                            channel, message.getRequestId(), message.getType(), "unsupported-type"));
                    NameserverChannelSupport.reply(
                            channel,
                            message.getRequestId(),
                            message.oneway(),
                            CommonResponseBody.fail(
                                    StatusConstants.BAD_REQUEST,
                                    "不支持的消息类型: " + ProtocolTypeNames.nameOf(message.getType())));
                }
            }
        } catch (Exception ex) {
            log.warn("{}", NameserverTrace.of(
                    channel, message.getRequestId(), message.getType(), "map-event-failed"), ex);
            NameserverChannelSupport.reply(
                    channel,
                    message.getRequestId(),
                    message.oneway(),
                    CommonResponseBody.fail(StatusConstants.SERVER_ERROR, ex.getMessage()));
        }
    }

    /** 连接断开 → ChannelInactiveEvent */
    public void onChannelInactive(Channel channel) {
        log.info("action=channel-inactive, remote={}", NameserverTrace.remote(channel));
        eventBus.publish(ChannelInactiveEvent.of(channel));
    }

    private void publishRegister(Channel channel, RoverMessage message) {
        RegisterRequest request = RoverMessageCodecSupport.decodeBody(message, RegisterRequest.class);
        if (!authorized(channel, message, request.getToken())) {
            return;
        }
        RegisterEvent event = new RegisterEvent();
        fillBase(event, channel, message);
        event.setRequest(request);
        log.debug("{}", NameserverTrace.of(event, "publish-RegisterEvent"));
        eventBus.publish(event);
    }

    private void publishUnregister(Channel channel, RoverMessage message) {
        UnregisterRequest request = RoverMessageCodecSupport.decodeBody(message, UnregisterRequest.class);
        if (!authorized(channel, message, request.getToken())) {
            return;
        }
        UnregisterEvent event = new UnregisterEvent();
        fillBase(event, channel, message);
        event.setRequest(request);
        log.debug("{}", NameserverTrace.of(event, "publish-UnregisterEvent"));
        eventBus.publish(event);
    }

    private void publishHeartbeat(Channel channel, RoverMessage message) {
        HeartbeatEvent event = new HeartbeatEvent();
        fillBase(event, channel, message);
        event.setRequest(RoverMessageCodecSupport.decodeBody(message, HeartbeatRequest.class));
        log.debug("{}", NameserverTrace.of(event, "publish-HeartbeatEvent"));
        eventBus.publish(event);
    }

    private void publishQuery(Channel channel, RoverMessage message) {
        QueryEvent event = new QueryEvent();
        fillBase(event, channel, message);
        event.setRequest(RoverMessageCodecSupport.decodeBody(message, QueryRequest.class));
        log.debug("{}", NameserverTrace.of(event, "publish-QueryEvent"));
        eventBus.publish(event);
    }

    private void publishSubscribe(Channel channel, RoverMessage message) {
        SubscribeRequest request = RoverMessageCodecSupport.decodeBody(message, SubscribeRequest.class);
        if (!authorized(channel, message, request.getToken())) {
            return;
        }
        SubscribeEvent event = new SubscribeEvent();
        fillBase(event, channel, message);
        event.setRequest(request);
        log.debug("{}", NameserverTrace.of(event, "publish-SubscribeEvent"));
        eventBus.publish(event);
    }

    private void publishUnsubscribe(Channel channel, RoverMessage message) {
        UnsubscribeEvent event = new UnsubscribeEvent();
        fillBase(event, channel, message);
        event.setRequest(RoverMessageCodecSupport.decodeBody(message, UnsubscribeRequest.class));
        log.debug("{}", NameserverTrace.of(event, "publish-UnsubscribeEvent"));
        eventBus.publish(event);
    }

    private void fillBase(NameserverChannelEvent event, Channel channel, RoverMessage message) {
        event.setChannel(channel);
        event.setRequestId(message.getRequestId());
        event.setOneway(message.oneway());
        event.setAckMode(NameserverChannelSupport.resolveAck(services, message));
    }

    /**
     * 校验协议层 token。服务端未配置 token 时放行；配置后必须与请求携带的 token 完全一致，
     * 否则回 401 并阻止事件进入业务 Listener，实现注册/注销/订阅的纵深防御。
     */
    private boolean authorized(Channel channel, RoverMessage message, String providedToken) {
        String expected = services.getOptions().getToken();
        if (expected == null || expected.isBlank()) {
            return true;
        }
        if (expected.equals(providedToken)) {
            return true;
        }
        log.warn("{}", NameserverTrace.of(
                channel, message.getRequestId(), message.getType(), "unauthorized-token"));
        NameserverChannelSupport.replyFail(
                channel, message.getRequestId(), message.oneway(),
                StatusConstants.UNAUTHORIZED, "鉴权失败：token 不匹配");
        return false;
    }
}
