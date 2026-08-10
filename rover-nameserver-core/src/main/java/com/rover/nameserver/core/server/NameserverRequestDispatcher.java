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
 *
 * 核心职责：Nameserver 的请求处理中枢。接收解码后的 {@link RoverMessage}，
 * 按消息类型 switch 分发到注册、注销、心跳、查询、订阅、退订六类业务处理，
 * 统一负责参数校验、ACK 协商、结果响应与变更推送的触发。</p>
 *
 * 被 {@link NameserverServerHandler}（Netty 业务线程组）调用；
 * 依赖的兄弟组件：{@link ServiceRegistry}（数据面）、{@link SubscriptionManager}
 * 与 {@link PushService}（订阅推送链路）、{@link WriteAckPolicy}（确认语义）、
 * {@link NameserverServerOptions}（服务端参数）。</p>
 *
 * 连接状态管理：每个连接在 channel 属性上登记它注册过的实例集合
 * （{@link #BOUND_INSTANCES}）。连接断开时 {@link #onChannelInactive}
 * 据此自动注销这些实例并广播——这是「客户端进程崩溃来不及主动注销」
 * 场景下的恢复机制。</p>
 */
@Slf4j
public class NameserverRequestDispatcher {

    /** 挂在 channel 上：这条连接注册过哪些 service#instance */
    static final AttributeKey<Set<String>> BOUND_INSTANCES =
            AttributeKey.valueOf("nameserverBoundInstances");

    /** 注册表：数据面 */
    private final ServiceRegistry registry;
    /** 订阅关系管理器：维护哪些连接订阅了哪些服务 */
    private final SubscriptionManager subscriptionManager;
    /** 推送服务：变更后广播快照 */
    private final PushService pushService;
    /** 写确认策略：协商 ACK 模式与所需确认数 */
    private final WriteAckPolicy writeAckPolicy;
    /** 服务端运行参数（节点 ID、副本数、集群开关等） */
    private final NameserverServerOptions options;

    /**
     * 构造分发器，注入全部业务依赖。
     *
     * @param registry             注册表
     * @param subscriptionManager  订阅关系管理器
     * @param pushService          推送服务
     * @param writeAckPolicy       写确认策略
     * @param options              服务端运行参数
     */
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

    /**
     * 分发入口：按消息类型分发到各处理分支。
     * 任何处理异常（解码失败/校验失败/注册表异常）都会被捕获并回复
     * SERVER_ERROR，避免异常冒泡到 Netty 断开连接。
     *
     * @param channel 来源连接
     * @param message 解码后的协议消息；null 直接忽略
     */
    public void dispatch(Channel channel, RoverMessage message) {
        if (message == null) {
            return;
        }
        try {
            // 请求类型分发：客户端协议常量与处理分支一一对应
            switch (message.getType()) {
                case ProtocolConstants.REGISTER_REQUEST -> handleRegister(channel, message);
                case ProtocolConstants.UNREGISTER_REQUEST -> handleUnregister(channel, message);
                case ProtocolConstants.HEARTBEAT_REQUEST -> handleHeartbeat(channel, message);
                case ProtocolConstants.QUERY_REQUEST -> handleQuery(channel, message);
                case ProtocolConstants.SUBSCRIBE_REQUEST -> handleSubscribe(channel, message);
                case ProtocolConstants.UNSUBSCRIBE_REQUEST -> handleUnsubscribe(channel, message);
                // 未知类型：按参数错误拒绝，不中断连接
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

    /**
     * 连接断开恢复逻辑：先清理该连接的全部订阅关系，再按
     * channel 属性里登记的实例（service#instance 集合）逐一注销，
     * 每次注销产生快照即推送——保证客户端进程崩溃后其实例
     * 不会长期残留占用注册表。
     *
     * @param channel 已断开的连接
     */
    /** 连接断了：清订阅，并把这条连接绑过的实例摘掉 */
    public void onChannelInactive(Channel channel) {
        subscriptionManager.removeChannel(channel);
        // 取并清空绑定集合，防止重复回调时二次清理
        Set<String> bound = channel.attr(BOUND_INSTANCES).getAndSet(null);
        if (bound == null || bound.isEmpty()) {
            return;
        }
        for (String key : bound) {
            // 绑定格式为 serviceName#instanceId，拆解后注销
            String[] parts = key.split("#", 2);
            if (parts.length != 2) {
                continue;
            }
            RegistrySnapshot snapshot = registry.unregister(parts[0], parts[1]);
            // 快照非 null 才推送：该实例可能已被主动注销（unbind 已摘绑定，正常不会发生）
            if (snapshot != null) {
                pushService.pushSnapshot(snapshot);
            }
        }
    }

    /**
     * 处理注册：校验参数 → 协商 ACK → 写入注册表 → 绑定实例到连接
     * （保证断线可自动清理）→ 推送变更 → 回复含 revision 的成功响应。
     */
    private void handleRegister(Channel channel, RoverMessage message) {
        RegisterRequest request = RoverMessageCodecSupport.decodeBody(message, RegisterRequest.class);
        if (!validRegister(request)) {
            reply(channel, message, CommonResponseBody.fail(StatusConstants.BAD_REQUEST, "注册参数不完整"));
            return;
        }

        AckMode ackMode = resolveAck(message);
        RegistrySnapshot snapshot = registry.register(request);
        // 绑到连接上，进程挂了来不及 unregister 也能清掉
        bindInstance(channel, request.getServiceName(), request.getInstanceId());
        pushService.pushSnapshot(snapshot);

        // 随响应带回 ACK 语义与所需确认数，客户端据此判断写入强度
        CommonResponseBody body = CommonResponseBody.success()
                .withAck(ackMode, writeAckPolicy.requiredAcks(
                        ackMode, options.getReplicationFactor(), options.isClusterEnabled()));
        body.setRevision(snapshot.getRevision());
        fillNode(body);
        reply(channel, message, body);
    }

    /**
     * 处理注销：校验参数 → 协商 ACK → 从注册表移除 → 摘除连接绑定
     * （避免后续断线清理重复注销）→ 实例存在则推送变更。
     */
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
            // 实例不存在：不是致命错误，按业务错误回复即可
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

    /**
     * 处理心跳：刷新注册表中心跳时间（隐含恢复健康状态），
     * 返回该服务当前 revision 供客户端对比。
     */
    private void handleHeartbeat(Channel channel, RoverMessage message) {
        HeartbeatRequest request = RoverMessageCodecSupport.decodeBody(message, HeartbeatRequest.class);
        if (request.getServiceName() == null || request.getInstanceId() == null
                || request.getServiceName().isBlank() || request.getInstanceId().isBlank()) {
            reply(channel, message, CommonResponseBody.fail(StatusConstants.BAD_REQUEST, "心跳参数不完整"));
            return;
        }
        boolean ok = registry.heartbeat(request.getServiceName(), request.getInstanceId());
        if (!ok) {
            // 实例不存在：多数发生在注册中心重启/实例被剔除后，提示客户端重新注册
            reply(channel, message, CommonResponseBody.fail(StatusConstants.SERVICE_NOT_FOUND, "实例不存在，请先注册"));
            return;
        }
        CommonResponseBody body = CommonResponseBody.success();
        body.setRevision(registry.revisionOf(request.getServiceName()));
        fillNode(body);
        reply(channel, message, body);
    }

    /**
     * 处理查询：按服务/组/健康过滤取实例，序列化进响应体，
     * 并附带服务 revision 帮助客户端判断缓存是否过期。
     */
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

        // 查询结果放进通用响应体的 payload 字段（protostuff 序列化字节）
        CommonResponseBody body = CommonResponseBody.success(ProtostuffSerializer.serialize(queryBody));
        body.setRevision(queryBody.getRevision());
        fillNode(body);
        reply(channel, message, body);
    }

    /**
     * 处理订阅：登记订阅关系后立即用「当前全量快照」推送一次，
     * 使客户端无需额外 query 即可建立初始状态，同时返回当前 revision。
     */
    private void handleSubscribe(Channel channel, RoverMessage message) {
        SubscribeRequest request = RoverMessageCodecSupport.decodeBody(message, SubscribeRequest.class);
        if (request.getServiceName() == null || request.getServiceName().isBlank()) {
            reply(channel, message, CommonResponseBody.fail(StatusConstants.BAD_REQUEST, "serviceName 不能为空"));
            return;
        }
        subscriptionManager.subscribe(request.getServiceName(), request.getGroup(), channel);

        // 订上立刻推当前全量，客户端不用再 query 一次
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

    /** 处理退订：移除该连接的订阅关系（不影响其他连接/通配订阅） */
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

    /**
     * 协商本次请求的 ACK 模式：服务端默认值 + 客户端请求 + 是否允许覆盖，
     * 交由 WriteAckPolicy 决定最终模式。
     */
    private AckMode resolveAck(RoverMessage message) {
        return writeAckPolicy.resolve(
                options.getWriteAckMode(),
                message.ackMode(),
                options.isAllowClientAckOverride());
    }

    /**
     * 回复请求：oneway（不期望响应）消息直接跳过发送；
     * 否则带上原 requestId 回复，保证客户端 pending 匹配。
     */
    private void reply(Channel channel, RoverMessage request, CommonResponseBody body) {
        if (request.oneway()) {
            return;
        }
        // 必须带回原 requestId，客户端 pending 才能对上
        channel.writeAndFlush(RoverMessageCodecSupport.response(request.getRequestId(), body));
    }

    /** 服务端配置了节点 ID 时，在响应中标注来源节点（多节点排障用） */
    private void fillNode(CommonResponseBody body) {
        if (options.getNodeId() != null && !options.getNodeId().isBlank()) {
            body.setNodeId(options.getNodeId());
        }
    }

    /** 注册参数完整性校验：服务名/主机/实例 ID 非空且端口合法 */
    private boolean validRegister(RegisterRequest request) {
        return request != null
                && request.getServiceName() != null && !request.getServiceName().isBlank()
                && request.getHost() != null && !request.getHost().isBlank()
                && request.getInstanceId() != null && !request.getInstanceId().isBlank()
                && request.getPort() > 0;
    }

    /**
     * 把 (serviceName, instanceId) 登记到连接的 BOUND_INSTANCES 属性。
     * 连接断开时据此自动注销实例（断线恢复机制的数据基础）。
     */
    /** register 成功后调用 */
    private void bindInstance(Channel channel, String serviceName, String instanceId) {
        Set<String> bound = channel.attr(BOUND_INSTANCES).get();
        if (bound == null) {
            bound = ConcurrentHashMap.newKeySet();
            channel.attr(BOUND_INSTANCES).set(bound);
        }
        bound.add(serviceName + "#" + instanceId);
    }

    /**
     * 主动注销时同步摘除连接绑定，避免断线回调再次注销同一实例（幂等保护）。
     */
    /** 主动 unregister 时同步摘掉绑定，避免断连时重复清理 */
    private void unbindInstance(Channel channel, String serviceName, String instanceId) {
        Set<String> bound = channel.attr(BOUND_INSTANCES).get();
        if (bound != null) {
            bound.remove(serviceName + "#" + instanceId);
        }
    }
}