package com.rover.nameserver.core.server;

import com.rover.common.constants.ProtocolConstants;
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
import com.rover.nameserver.client.codec.RoverMessageCodecSupport;
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
import io.netty.channel.Channel;
import lombok.extern.slf4j.Slf4j;

/**
 * Author: Daylight
 * Created: 2026-08-08 17:50:00
 * Description: 协议入口映射器（aero-mq 同构）
 *
 * 只做：解码 → 组装 XxxEvent → eventBus.publish。
 * 业务全在 event/spi/listener 里，不在这里写注册/推送逻辑。
 */
@Slf4j
public class NameserverRequestDispatcher {

    private final EventBus eventBus;
    private final NameserverServices services;

    public NameserverRequestDispatcher(EventBus eventBus, NameserverServices services) {
        this.eventBus = eventBus;
        this.services = services;
    }

    /**
     * 按消息类型映射成事件并异步发布。
     * 解码失败等异常在此捕获并回 SERVER_ERROR，避免打爆 Netty。
     */
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
                default -> NameserverChannelSupport.reply(
                        channel,
                        message.getRequestId(),
                        message.oneway(),
                        CommonResponseBody.fail(
                                StatusConstants.BAD_REQUEST,
                                "不支持的消息类型: " + message.getType()));
            }
        } catch (Exception ex) {
            log.warn("映射协议事件失败, type={}, requestId={}", message.getType(), message.getRequestId(), ex);
            NameserverChannelSupport.reply(
                    channel,
                    message.getRequestId(),
                    message.oneway(),
                    CommonResponseBody.fail(StatusConstants.SERVER_ERROR, ex.getMessage()));
        }
    }

    /** 连接断开 → ChannelInactiveEvent */
    public void onChannelInactive(Channel channel) {
        eventBus.publish(ChannelInactiveEvent.of(channel));
    }

    private void publishRegister(Channel channel, RoverMessage message) {
        RegisterEvent event = new RegisterEvent();
        fillBase(event, channel, message);
        event.setRequest(RoverMessageCodecSupport.decodeBody(message, RegisterRequest.class));
        eventBus.publish(event);
    }

    private void publishUnregister(Channel channel, RoverMessage message) {
        UnregisterEvent event = new UnregisterEvent();
        fillBase(event, channel, message);
        event.setRequest(RoverMessageCodecSupport.decodeBody(message, UnregisterRequest.class));
        eventBus.publish(event);
    }

    private void publishHeartbeat(Channel channel, RoverMessage message) {
        HeartbeatEvent event = new HeartbeatEvent();
        fillBase(event, channel, message);
        event.setRequest(RoverMessageCodecSupport.decodeBody(message, HeartbeatRequest.class));
        eventBus.publish(event);
    }

    private void publishQuery(Channel channel, RoverMessage message) {
        QueryEvent event = new QueryEvent();
        fillBase(event, channel, message);
        event.setRequest(RoverMessageCodecSupport.decodeBody(message, QueryRequest.class));
        eventBus.publish(event);
    }

    private void publishSubscribe(Channel channel, RoverMessage message) {
        SubscribeEvent event = new SubscribeEvent();
        fillBase(event, channel, message);
        event.setRequest(RoverMessageCodecSupport.decodeBody(message, SubscribeRequest.class));
        eventBus.publish(event);
    }

    private void publishUnsubscribe(Channel channel, RoverMessage message) {
        UnsubscribeEvent event = new UnsubscribeEvent();
        fillBase(event, channel, message);
        event.setRequest(RoverMessageCodecSupport.decodeBody(message, UnsubscribeRequest.class));
        eventBus.publish(event);
    }

    private void fillBase(NameserverChannelEvent event, Channel channel, RoverMessage message) {
        event.setChannel(channel);
        event.setRequestId(message.getRequestId());
        event.setOneway(message.oneway());
        event.setAckMode(NameserverChannelSupport.resolveAck(services, message));
    }
}
