package com.rover.nameserver.core.event.support;

import com.rover.common.constants.StatusConstants;
import com.rover.common.protocol.AckMode;
import com.rover.common.protocol.CommonResponseBody;
import com.rover.common.protocol.RoverMessage;
import com.rover.common.codec.RoverMessageCodecSupport;
import com.rover.nameserver.core.server.NameserverServerOptions;
import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Author: Daylight
 * Created: 2026-08-06 10:50:00
 * Description: 连接绑定、回包与 ACK 协商等 Listener 共用工具类
 */
public final class NameserverChannelSupport {

    /** 挂在 channel 上：这条连接注册过哪些 service#instance */
    public static final AttributeKey<Set<String>> BOUND_INSTANCES =
            AttributeKey.valueOf("nameserverBoundInstances");

    private NameserverChannelSupport() {
    }

    public static AckMode resolveAck(
            NameserverServices services, RoverMessage message) {
        return services.getWriteAckPolicy().resolve(
                services.getOptions().getWriteAckMode(),
                message.ackMode(),
                services.getOptions().isAllowClientAckOverride());
    }

    public static void reply(Channel channel, long requestId, boolean oneway, CommonResponseBody body) {
        if (oneway || channel == null) {
            return;
        }
        channel.writeAndFlush(RoverMessageCodecSupport.response(requestId, body));
    }

    public static void replyFail(
            Channel channel, long requestId, boolean oneway, int status, String message) {
        reply(channel, requestId, oneway, CommonResponseBody.fail(status, message));
    }

    public static void fillNode(NameserverServerOptions options, CommonResponseBody body) {
        if (options.getNodeId() != null && !options.getNodeId().isBlank()) {
            body.setNodeId(options.getNodeId());
        }
    }

    /** 回包时带上世代信息（epoch / 预留 leaderHint），集群实现可丰富 Generation。 */
    public static void fillGeneration(NameserverServices services, CommonResponseBody body) {
        if (services == null || body == null || services.getGeneration() == null) {
            return;
        }
        body.setEpoch(services.getEpoch());
        String leaderHint = services.getGeneration().leaderHint();
        if (leaderHint != null && !leaderHint.isBlank()) {
            body.setLeaderHint(leaderHint);
        }
    }

    public static void bindInstance(Channel channel, String serviceName, String instanceId) {
        Set<String> bound = channel.attr(BOUND_INSTANCES).get();
        if (bound == null) {
            bound = ConcurrentHashMap.newKeySet();
            channel.attr(BOUND_INSTANCES).set(bound);
        }
        bound.add(serviceName + "#" + instanceId);
    }

    public static void unbindInstance(Channel channel, String serviceName, String instanceId) {
        Set<String> bound = channel.attr(BOUND_INSTANCES).get();
        if (bound != null) {
            bound.remove(serviceName + "#" + instanceId);
        }
    }

    public static Set<String> takeBoundInstances(Channel channel) {
        return channel.attr(BOUND_INSTANCES).getAndSet(null);
    }

    public static CommonResponseBody badRequest(String message) {
        return CommonResponseBody.fail(StatusConstants.BAD_REQUEST, message);
    }
}
