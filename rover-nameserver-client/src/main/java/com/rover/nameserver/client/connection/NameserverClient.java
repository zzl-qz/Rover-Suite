package com.rover.nameserver.client.connection;

import com.rover.common.concurrent.PendingRequestTable;
import com.rover.common.concurrent.PeriodicTask;
import com.rover.common.concurrent.RequestIdGenerator;
import com.rover.common.constants.ProtocolConstants;
import com.rover.common.constants.StatusConstants;
import com.rover.common.exception.RoverException;
import com.rover.common.model.ServiceInstance;
import com.rover.common.protocol.AckMode;
import com.rover.common.protocol.CommonResponseBody;
import com.rover.common.protocol.HeartbeatRequest;
import com.rover.common.protocol.QueryRequest;
import com.rover.common.protocol.QueryResponseBody;
import com.rover.common.protocol.RegisterRequest;
import com.rover.common.protocol.RoverMessage;
import com.rover.common.protocol.ServicePushBody;
import com.rover.common.protocol.SubscribeRequest;
import com.rover.common.protocol.UnregisterRequest;
import com.rover.common.protocol.UnsubscribeRequest;
import com.rover.nameserver.client.cache.InstanceCache;
import com.rover.nameserver.client.codec.ProtostuffSerializer;
import com.rover.nameserver.client.codec.RoverMessageCodecSupport;
import com.rover.nameserver.client.codec.RoverMessageDecoder;
import com.rover.nameserver.client.codec.RoverMessageEncoder;
import com.rover.nameserver.client.handler.NameserverClientHandler;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Author: Daylight
 * Created: 2026-08-08 17:55:00
 * Description: Nameserver TCP 客户端，带请求响应匹配和简单心跳
 */
@Slf4j
public class NameserverClient implements AutoCloseable {

    @Getter
    private final NameserverClientOptions options;
    @Getter
    private final InstanceCache instanceCache = new InstanceCache();

    private final RequestIdGenerator requestIdGenerator = new RequestIdGenerator();
    private final PendingRequestTable<CommonResponseBody> pendingRequests;
    private final Map<String, RegisterRequest> registeredInstances = new ConcurrentHashMap<>();
    private final Map<String, SubscribeRequest> subscriptions = new ConcurrentHashMap<>();
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean reconnecting = new AtomicBoolean(false);

    private EventLoopGroup workerGroup;
    private volatile Channel channel;
    private PeriodicTask heartbeatTask;
    private PeriodicTask reconnectTask;

    public NameserverClient(NameserverClientOptions options) {
        this.options = Objects.requireNonNull(options, "options");
        this.pendingRequests = new PendingRequestTable<>(options.getMaxPendingRequests());
    }

    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        workerGroup = new NioEventLoopGroup();
        connect();
        if (options.isAutoHeartbeat()) {
            // 固定间隔心跳就够了，没必要上时间轮
            heartbeatTask = new PeriodicTask("nameserver-client-heartbeat");
            heartbeatTask.start(this::heartbeatRegistered, options.getHeartbeatIntervalMs(), options.getHeartbeatIntervalMs());
        }
        if (options.isAutoReconnect()) {
            reconnectTask = new PeriodicTask("nameserver-client-reconnect");
            reconnectTask.start(this::tryReconnect, options.getReconnectIntervalMs(), options.getReconnectIntervalMs());
        }
    }

    public boolean isActive() {
        Channel current = channel;
        return current != null && current.isActive();
    }

    public CommonResponseBody register(RegisterRequest request) {
        CommonResponseBody response = requestSync(ProtocolConstants.REGISTER_REQUEST, request);
        ensureSuccess(response, "注册失败");
        registeredInstances.put(instanceKey(request.getServiceName(), request.getInstanceId()), copyRegister(request));
        return response;
    }

    public CommonResponseBody unregister(String serviceName, String instanceId) {
        UnregisterRequest request = new UnregisterRequest();
        request.setServiceName(serviceName);
        request.setInstanceId(instanceId);
        CommonResponseBody response = requestSync(ProtocolConstants.UNREGISTER_REQUEST, request);
        registeredInstances.remove(instanceKey(serviceName, instanceId));
        ensureSuccess(response, "注销失败");
        return response;
    }

    public CommonResponseBody heartbeat(String serviceName, String instanceId) {
        HeartbeatRequest request = new HeartbeatRequest();
        request.setServiceName(serviceName);
        request.setInstanceId(instanceId);
        request.setClientTimeMillis(System.currentTimeMillis());
        return requestSync(ProtocolConstants.HEARTBEAT_REQUEST, request);
    }

    public QueryResponseBody query(String serviceName, String group, boolean healthyOnly) {
        QueryRequest request = new QueryRequest();
        request.setServiceName(serviceName);
        request.setGroup(group);
        request.setHealthyOnly(healthyOnly);

        CommonResponseBody response = requestSync(ProtocolConstants.QUERY_REQUEST, request);
        ensureSuccess(response, "查询失败");
        QueryResponseBody body = ProtostuffSerializer.deserialize(response.getData(), QueryResponseBody.class);
        instanceCache.putSnapshot(serviceName, group, body.getRevision(), body.getInstances());
        return body;
    }

    public List<ServiceInstance> getCachedInstances(String serviceName, String group) {
        return instanceCache.get(serviceName, group);
    }

    public CommonResponseBody subscribe(String serviceName, String group) {
        SubscribeRequest request = new SubscribeRequest();
        request.setServiceName(serviceName);
        request.setGroup(group);
        request.setKnownRevision(instanceCache.revision(serviceName, group));

        CommonResponseBody response = requestSync(ProtocolConstants.SUBSCRIBE_REQUEST, request);
        ensureSuccess(response, "订阅失败");
        subscriptions.put(subscribeKey(serviceName, group), request);
        return response;
    }

    public CommonResponseBody unsubscribe(String serviceName, String group) {
        UnsubscribeRequest request = new UnsubscribeRequest();
        request.setServiceName(serviceName);
        request.setGroup(group);
        CommonResponseBody response = requestSync(ProtocolConstants.UNSUBSCRIBE_REQUEST, request);
        subscriptions.remove(subscribeKey(serviceName, group));
        ensureSuccess(response, "取消订阅失败");
        return response;
    }

    public void addPushListener(Consumer<ServicePushBody> listener) {
        instanceCache.addListener(listener);
    }

    public CompletableFuture<CommonResponseBody> requestAsync(byte type, Object body) {
        return requestAsync(type, body, AckMode.IMMEDIATE, options.getRequestTimeoutMs());
    }

    public CompletableFuture<CommonResponseBody> requestAsync(
            byte type, Object body, AckMode ackMode, int timeoutMs) {
        ensureStarted();
        Channel current = requireActiveChannel();
        long requestId = requestIdGenerator.next();
        CompletableFuture<CommonResponseBody> future = pendingRequests.create(requestId, timeoutMs);
        RoverMessage message = RoverMessageCodecSupport.request(type, requestId, ackMode, timeoutMs, body);
        current.writeAndFlush(message).addListener(writeFuture -> {
            if (!writeFuture.isSuccess()) {
                pendingRequests.fail(requestId, writeFuture.cause() == null
                        ? new IllegalStateException("写入失败")
                        : writeFuture.cause());
            }
        });
        return future;
    }

    public CommonResponseBody requestSync(byte type, Object body) {
        try {
            return requestAsync(type, body).get(options.getRequestTimeoutMs() + 1000L, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            throw new RoverException("请求超时", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RoverException("请求被中断", ex);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new RoverException(cause.getMessage(), cause);
        }
    }

    public void onDisconnected() {
        this.channel = null;
        // 真正重连交给 reconnectTask，避免在 IO 线程里狂连
        reconnecting.set(true);
    }

    private void connect() {
        try {
            Bootstrap bootstrap = new Bootstrap();
            bootstrap.group(workerGroup)
                    .channel(NioSocketChannel.class)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, options.getConnectTimeoutMs())
                    .option(ChannelOption.SO_KEEPALIVE, true)
                    .option(ChannelOption.TCP_NODELAY, true)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline()
                                    .addLast(new RoverMessageDecoder())
                                    .addLast(new RoverMessageEncoder())
                                    .addLast(new NameserverClientHandler(pendingRequests, instanceCache, NameserverClient.this));
                        }
                    });

            ChannelFuture future = bootstrap.connect(options.getHost(), options.getPort()).sync();
            this.channel = future.channel();
            reconnecting.set(false);
            log.info("已连接 Nameserver: {}:{}", options.getHost(), options.getPort());
            recoverState();
        } catch (Exception ex) {
            this.channel = null;
            reconnecting.set(true);
            log.warn("连接 Nameserver 失败: {}:{}", options.getHost(), options.getPort(), ex);
        }
    }

    private void tryReconnect() {
        if (!started.get() || !options.isAutoReconnect()) {
            return;
        }
        if (isActive()) {
            reconnecting.set(false);
            return;
        }
        if (!reconnecting.get() && channel != null) {
            return;
        }
        log.info("尝试重连 Nameserver: {}:{}", options.getHost(), options.getPort());
        connect();
    }

    private void recoverState() {
        // 重连后把本地记住的注册和订阅补回去
        for (RegisterRequest request : registeredInstances.values()) {
            try {
                requestSync(ProtocolConstants.REGISTER_REQUEST, request);
            } catch (Exception ex) {
                log.warn("重连后恢复注册失败: {}#{}", request.getServiceName(), request.getInstanceId(), ex);
            }
        }
        for (SubscribeRequest request : subscriptions.values()) {
            try {
                requestSync(ProtocolConstants.SUBSCRIBE_REQUEST, request);
            } catch (Exception ex) {
                log.warn("重连后恢复订阅失败: {}", request.getServiceName(), ex);
            }
        }
    }

    private void heartbeatRegistered() {
        if (!isActive() || registeredInstances.isEmpty()) {
            return;
        }
        for (RegisterRequest request : registeredInstances.values()) {
            try {
                CommonResponseBody response = heartbeat(request.getServiceName(), request.getInstanceId());
                if (response.getCode() != StatusConstants.SUCCESS) {
                    log.warn("心跳失败: {}#{}, code={}, msg={}",
                            request.getServiceName(),
                            request.getInstanceId(),
                            response.getCode(),
                            response.getMessage());
                }
            } catch (Exception ex) {
                log.warn("发送心跳异常: {}#{}", request.getServiceName(), request.getInstanceId(), ex);
            }
        }
    }

    private Channel requireActiveChannel() {
        Channel current = channel;
        if (current == null || !current.isActive()) {
            throw new RoverException("尚未连接到 Nameserver");
        }
        return current;
    }

    private void ensureStarted() {
        if (!started.get()) {
            throw new RoverException("NameserverClient 尚未 start");
        }
    }

    private void ensureSuccess(CommonResponseBody response, String prefix) {
        if (response == null) {
            throw new RoverException(prefix + ": 空响应");
        }
        if (response.getCode() != StatusConstants.SUCCESS) {
            throw new RoverException(prefix + ": code=" + response.getCode() + ", message=" + response.getMessage());
        }
    }

    private String instanceKey(String serviceName, String instanceId) {
        return serviceName + "#" + instanceId;
    }

    private String subscribeKey(String serviceName, String group) {
        return serviceName + "#" + (group == null ? "" : group);
    }

    private RegisterRequest copyRegister(RegisterRequest source) {
        RegisterRequest copy = new RegisterRequest();
        copy.setServiceName(source.getServiceName());
        copy.setHost(source.getHost());
        copy.setPort(source.getPort());
        copy.setInstanceId(source.getInstanceId());
        copy.setRegisterTime(source.getRegisterTime());
        copy.setWeight(source.getWeight());
        copy.setGroup(source.getGroup());
        copy.setZone(source.getZone());
        copy.setEphemeral(source.isEphemeral());
        copy.setMetadata(source.getMetadata() == null
                ? new java.util.HashMap<>()
                : new java.util.HashMap<>(source.getMetadata()));
        return copy;
    }

    @Override
    public void close() {
        shutdown();
    }

    public void shutdown() {
        if (!started.compareAndSet(true, false)) {
            return;
        }
        if (heartbeatTask != null) {
            heartbeatTask.stop();
        }
        if (reconnectTask != null) {
            reconnectTask.stop();
        }
        pendingRequests.failAll(new IllegalStateException("客户端已关闭"));
        pendingRequests.close();
        Channel current = channel;
        if (current != null) {
            current.close();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
        registeredInstances.clear();
        subscriptions.clear();
        instanceCache.clear();
        log.info("NameserverClient 已关闭");
    }
}
