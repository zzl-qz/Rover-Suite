package com.rover.nameserver.core.server;

import com.rover.common.constants.ProtocolConstants;
import com.rover.common.constants.StatusConstants;
import com.rover.common.model.ServiceInstance;
import com.rover.common.protocol.AckMode;
import com.rover.common.protocol.CommonResponseBody;
import com.rover.common.protocol.HeartbeatRequest;
import com.rover.common.protocol.QueryRequest;
import com.rover.common.protocol.QueryResponseBody;
import com.rover.common.protocol.RegisterRequest;
import com.rover.common.protocol.RoverMessage;
import com.rover.common.protocol.SubscribeRequest;
import com.rover.common.protocol.UnregisterRequest;
import com.rover.common.protocol.UnsubscribeRequest;
import com.rover.nameserver.client.codec.ProtostuffSerializer;
import com.rover.nameserver.client.codec.RoverMessageCodecSupport;
import com.rover.nameserver.core.consistency.WriteAckPolicy;
import com.rover.nameserver.core.push.PushService;
import com.rover.nameserver.core.push.SubscriptionManager;
import com.rover.nameserver.core.registry.RegistrySnapshot;
import com.rover.nameserver.core.registry.ServiceRegistry;
import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;

/**
 * Author: Daylight
 * Created: 2026-08-08 17:50:00
 * Description: 按消息类型分发处理
 */
@Slf4j
public class NameserverRequestDispatcher {

    static final AttributeKey<Set<String>> BOUND_INSTANCES =
            AttributeKey.valueOf("nameserverBoundInstances");

    private final ServiceRegistry registry;
    private final SubscriptionManager subscriptionManager;
    private final PushService pushService;
    private final WriteAckPolicy writeAckPolicy;
    private final NameserverServerOptions options;

    public NameserverRequestDispatcher(
            ServiceRegistry registry,
            SubscriptionManager subscriptionManager,
            PushService pushService,
            WriteAckPolicy writeAckPolicy,
            NameserverServerOptions options) {
        this.registry = registry;
        this.subscriptionManager = subscriptionManager;
        this.pushService = pushService;
        this.writeAckPolicy = writeAckPolicy;
        this.options = options;
    }

    public void dispatch(Channel channel, RoverMessage message) {
        if (message == null) {
            return;
        }
        try {
            switch (message.getType()) {
                case ProtocolConstants.REGISTER_REQUEST -> handleRegister(channel, message);
                case ProtocolConstants.UNREGISTER_REQUEST -> handleUnregister(channel, message);
                case ProtocolConstants.HEARTBEAT_REQUEST -> handleHeartbeat(channel, message);
                case ProtocolConstants.QUERY_REQUEST -> handleQuery(channel, message);
                case ProtocolConstants.SUBSCRIBE_REQUEST -> handleSubscribe(channel, message);
                case ProtocolConstants.UNSUBSCRIBE_REQUEST -> handleUnsubscribe(channel, message);
                default -> reply(
                        channel,
                        message,
                        CommonResponseBody.fail(StatusConstants.BAD_REQUEST, "不支持的消息类型: " + message.getType()));
            }
        } catch (Exception ex) {
            log.warn("处理消息失败, type={}, requestId={}", message.getType(), message.getRequestId(), ex);
            reply(channel, message, CommonResponseBody.fail(StatusConstants.SERVER_ERROR, ex.getMessage()));
        }
    }

    public void onChannelInactive(Channel channel) {
        subscriptionManager.removeChannel(channel);
        Set<String> bound = channel.attr(BOUND_INSTANCES).getAndSet(null);
        if (bound == null || bound.isEmpty()) {
            return;
        }
        for (String key : bound) {
            String[] parts = key.split("#", 2);
            if (parts.length != 2) {
                continue;
            }
            RegistrySnapshot snapshot = registry.unregister(parts[0], parts[1]);
            if (snapshot != null) {
                pushService.pushSnapshot(snapshot);
            }
        }
    }

    private void handleRegister(Channel channel, RoverMessage message) {
        RegisterRequest request = RoverMessageCodecSupport.decodeBody(message, RegisterRequest.class);
        if (!validRegister(request)) {
            reply(channel, message, CommonResponseBody.fail(StatusConstants.BAD_REQUEST, "注册参数不完整"));
            return;
        }

        AckMode ackMode = resolveAck(message);
        RegistrySnapshot snapshot = registry.register(request);
        bindInstance(channel, request.getServiceName(), request.getInstanceId());
        pushService.pushSnapshot(snapshot);

        CommonResponseBody body = CommonResponseBody.success()
                .withAck(ackMode, writeAckPolicy.requiredAcks(
                        ackMode, options.getReplicationFactor(), options.isClusterEnabled()));
        body.setRevision(snapshot.getRevision());
        fillNode(body);
        reply(channel, message, body);
    }

    private void handleUnregister(Channel channel, RoverMessage message) {
        UnregisterRequest request = RoverMessageCodecSupport.decodeBody(message, UnregisterRequest.class);
        if (request.getServiceName() == null || request.getServiceName().isBlank()
                || request.getInstanceId() == null || request.getInstanceId().isBlank()) {
            reply(channel, message, CommonResponseBody.fail(StatusConstants.BAD_REQUEST, "注销参数不完整"));
            return;
        }

        AckMode ackMode = resolveAck(message);
        RegistrySnapshot snapshot = registry.unregister(request.getServiceName(), request.getInstanceId());
        unbindInstance(channel, request.getServiceName(), request.getInstanceId());
        if (snapshot == null) {
            reply(channel, message, CommonResponseBody.fail(StatusConstants.SERVICE_NOT_FOUND, "实例不存在"));
            return;
        }
        pushService.pushSnapshot(snapshot);

        CommonResponseBody body = CommonResponseBody.success()
                .withAck(ackMode, writeAckPolicy.requiredAcks(
                        ackMode, options.getReplicationFactor(), options.isClusterEnabled()));
        body.setRevision(snapshot.getRevision());
        fillNode(body);
        reply(channel, message, body);
    }

    private void handleHeartbeat(Channel channel, RoverMessage message) {
        HeartbeatRequest request = RoverMessageCodecSupport.decodeBody(message, HeartbeatRequest.class);
        if (request.getServiceName() == null || request.getInstanceId() == null
                || request.getServiceName().isBlank() || request.getInstanceId().isBlank()) {
            reply(channel, message, CommonResponseBody.fail(StatusConstants.BAD_REQUEST, "心跳参数不完整"));
            return;
        }
        boolean ok = registry.heartbeat(request.getServiceName(), request.getInstanceId());
        if (!ok) {
            reply(channel, message, CommonResponseBody.fail(StatusConstants.SERVICE_NOT_FOUND, "实例不存在，请先注册"));
            return;
        }
        CommonResponseBody body = CommonResponseBody.success();
        body.setRevision(registry.revisionOf(request.getServiceName()));
        fillNode(body);
        reply(channel, message, body);
    }

    private void handleQuery(Channel channel, RoverMessage message) {
        QueryRequest request = RoverMessageCodecSupport.decodeBody(message, QueryRequest.class);
        if (request.getServiceName() == null || request.getServiceName().isBlank()) {
            reply(channel, message, CommonResponseBody.fail(StatusConstants.BAD_REQUEST, "serviceName 不能为空"));
            return;
        }
        List<ServiceInstance> instances =
                registry.query(request.getServiceName(), request.getGroup(), request.isHealthyOnly());
        QueryResponseBody queryBody = new QueryResponseBody();
        queryBody.setInstances(instances);
        queryBody.setRevision(registry.revisionOf(request.getServiceName()));

        CommonResponseBody body = CommonResponseBody.success(ProtostuffSerializer.serialize(queryBody));
        body.setRevision(queryBody.getRevision());
        fillNode(body);
        reply(channel, message, body);
    }

    private void handleSubscribe(Channel channel, RoverMessage message) {
        SubscribeRequest request = RoverMessageCodecSupport.decodeBody(message, SubscribeRequest.class);
        if (request.getServiceName() == null || request.getServiceName().isBlank()) {
            reply(channel, message, CommonResponseBody.fail(StatusConstants.BAD_REQUEST, "serviceName 不能为空"));
            return;
        }
        subscriptionManager.subscribe(request.getServiceName(), request.getGroup(), channel);

        // 订上后先丢一份当前快照，省得客户端再查一次
        RegistrySnapshot snapshot = RegistrySnapshot.of(
                request.getServiceName(),
                request.getGroup(),
                registry.revisionOf(request.getServiceName()),
                registry.query(request.getServiceName(), request.getGroup(), false));
        pushService.pushSnapshot(snapshot);

        CommonResponseBody body = CommonResponseBody.success();
        body.setRevision(snapshot.getRevision());
        fillNode(body);
        reply(channel, message, body);
    }

    private void handleUnsubscribe(Channel channel, RoverMessage message) {
        UnsubscribeRequest request = RoverMessageCodecSupport.decodeBody(message, UnsubscribeRequest.class);
        if (request.getServiceName() == null || request.getServiceName().isBlank()) {
            reply(channel, message, CommonResponseBody.fail(StatusConstants.BAD_REQUEST, "serviceName 不能为空"));
            return;
        }
        subscriptionManager.unsubscribe(request.getServiceName(), request.getGroup(), channel);
        CommonResponseBody body = CommonResponseBody.success();
        fillNode(body);
        reply(channel, message, body);
    }

    private AckMode resolveAck(RoverMessage message) {
        return writeAckPolicy.resolve(
                options.getWriteAckMode(),
                message.ackMode(),
                options.isAllowClientAckOverride());
    }

    private void reply(Channel channel, RoverMessage request, CommonResponseBody body) {
        if (request.oneway()) {
            return;
        }
        channel.writeAndFlush(RoverMessageCodecSupport.response(request.getRequestId(), body));
    }

    private void fillNode(CommonResponseBody body) {
        if (options.getNodeId() != null && !options.getNodeId().isBlank()) {
            body.setNodeId(options.getNodeId());
        }
    }

    private boolean validRegister(RegisterRequest request) {
        return request != null
                && request.getServiceName() != null && !request.getServiceName().isBlank()
                && request.getHost() != null && !request.getHost().isBlank()
                && request.getInstanceId() != null && !request.getInstanceId().isBlank()
                && request.getPort() > 0;
    }

    private void bindInstance(Channel channel, String serviceName, String instanceId) {
        Set<String> bound = channel.attr(BOUND_INSTANCES).get();
        if (bound == null) {
            bound = ConcurrentHashMap.newKeySet();
            channel.attr(BOUND_INSTANCES).set(bound);
        }
        bound.add(serviceName + "#" + instanceId);
    }

    private void unbindInstance(Channel channel, String serviceName, String instanceId) {
        Set<String> bound = channel.attr(BOUND_INSTANCES).get();
        if (bound != null) {
            bound.remove(serviceName + "#" + instanceId);
        }
    }
}
