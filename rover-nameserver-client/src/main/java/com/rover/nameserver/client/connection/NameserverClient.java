package com.rover.nameserver.client.connection;

import com.rover.common.concurrent.PendingRequestTable;
import com.rover.common.constants.ProtocolTypeNames;
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
import com.rover.common.codec.ProtostuffSerializer;
import com.rover.common.codec.RoverMessageCodecSupport;
import com.rover.common.codec.RoverMessageDecoder;
import com.rover.common.codec.RoverMessageEncoder;
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
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
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
 * Description: Nameserver TCP 客户端门面：连接/请求配对 + 委托状态恢复给 ClientStateStore
 */
@Slf4j
public class NameserverClient implements AutoCloseable {

    /** 异步转同步时额外增加的等待时间，覆盖调度误差（毫秒）。 */
    private static final long SYNC_GRACE_MILLIS = 1000;

    @Getter
    private final NameserverClientOptions options;
    @Getter
    private final InstanceCache instanceCache = new InstanceCache();
    private final RequestIdGenerator requestIdGenerator = new RequestIdGenerator();
    private final PendingRequestTable<CommonResponseBody> pendingRequests;
    /** 注册/订阅本地状态与断线恢复。 */
    private final ClientStateStore stateStore = new ClientStateStore();
    private final ClientStateStore.SyncCaller syncCaller = new ClientStateStore.SyncCaller() {
        @Override
        public boolean isActive() {
            return NameserverClient.this.isActive();
        }

        @Override
        public CommonResponseBody requestSync(byte type, Object body) {
            return NameserverClient.this.requestSync(type, body);
        }

        @Override
        public CommonResponseBody heartbeat(String serviceName, String instanceId) {
            return NameserverClient.this.heartbeat(serviceName, instanceId);
        }
    };
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final ClientChannelState channelState = new ClientChannelState();
    private EventLoopGroup workerGroup;
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
        channelState.startAcceptingConnections();
        workerGroup = new NioEventLoopGroup();
        connect();
        if (options.isAutoHeartbeat()) {
            heartbeatTask = new PeriodicTask("nameserver-client-heartbeat");
            heartbeatTask.start(this::heartbeatRegistered, options.getHeartbeatIntervalMs(),
                    options.getHeartbeatIntervalMs());
        }
        if (options.isAutoReconnect()) {
            reconnectTask = new PeriodicTask("nameserver-client-reconnect");
            reconnectTask.start(this::tryReconnect, options.getReconnectIntervalMs(),
                    options.getReconnectIntervalMs());
        }
    }

    public boolean isActive() {
        return channelState.isActive();
    }

    public CommonResponseBody register(RegisterRequest request) {
        synchronized (stateStore.instanceLock(request.getServiceName(), request.getInstanceId())) {
            request.setToken(options.getToken());
            CommonResponseBody response = requestSync(ProtocolConstants.REGISTER_REQUEST, request);
            ensureSuccess(response, "注册失败");
            stateStore.rememberRegistered(request);
            return response;
        }
    }

    public CommonResponseBody unregister(String serviceName, String instanceId) {
        synchronized (stateStore.instanceLock(serviceName, instanceId)) {
            UnregisterRequest request = new UnregisterRequest();
            request.setServiceName(serviceName);
            request.setInstanceId(instanceId);
            request.setToken(options.getToken());
            CommonResponseBody response = requestSync(ProtocolConstants.UNREGISTER_REQUEST, request);
            ensureSuccess(response, "注销失败");
            stateStore.forgetRegistered(serviceName, instanceId);
            return response;
        }
    }

    public CommonResponseBody heartbeat(String serviceName, String instanceId) {
        HeartbeatRequest request = new HeartbeatRequest();
        request.setServiceName(serviceName);
        request.setInstanceId(instanceId);
        request.setClientTimeMillis(System.currentTimeMillis());
        request.setToken(options.getToken());
        return requestSync(ProtocolConstants.HEARTBEAT_REQUEST, request);
    }

    public QueryResponseBody query(String serviceName, String group, boolean healthyOnly) {
        QueryRequest request = new QueryRequest();
        request.setServiceName(serviceName);
        request.setGroup(group);
        request.setHealthyOnly(healthyOnly);
        request.setToken(options.getToken());

        CommonResponseBody response = requestSync(ProtocolConstants.QUERY_REQUEST, request);
        ensureSuccess(response, "查询失败");
        QueryResponseBody body = ProtostuffSerializer.deserialize(response.getData(), QueryResponseBody.class);
        String epoch = body.getEpoch() != null && !body.getEpoch().isBlank()
                ? body.getEpoch()
                : response.getEpoch();
        instanceCache.putSnapshotFromQuery(
                serviceName, group, epoch, body.getRevision(), body.getInstances());
        return body;
    }

    public List<ServiceInstance> getCachedInstances(String serviceName, String group) {
        return instanceCache.get(serviceName, group);
    }

    public CommonResponseBody subscribe(String serviceName, String group) {
        synchronized (stateStore.subscriptionLock(serviceName, group)) {
            SubscribeRequest request = new SubscribeRequest();
            request.setServiceName(serviceName);
            request.setGroup(group);
            request.setKnownRevision(instanceCache.revision(serviceName, group));
            request.setToken(options.getToken());

            CommonResponseBody response = requestSync(ProtocolConstants.SUBSCRIBE_REQUEST, request);
            ensureSuccess(response, "订阅失败");
            stateStore.rememberSubscribed(request);
            return response;
        }
    }

    public CommonResponseBody unsubscribe(String serviceName, String group) {
        synchronized (stateStore.subscriptionLock(serviceName, group)) {
            UnsubscribeRequest request = new UnsubscribeRequest();
            request.setServiceName(serviceName);
            request.setGroup(group);
            request.setToken(options.getToken());
            CommonResponseBody response = requestSync(ProtocolConstants.UNSUBSCRIBE_REQUEST, request);
            ensureSuccess(response, "取消订阅失败");
            stateStore.forgetSubscribed(serviceName, group);
            return response;
        }
    }

    /**
     * @DL 扩展 API：Gateway 内部直接读取缓存；外部客户端可用它接收已应用的推送。
     */
    public void addPushListener(Consumer<ServicePushBody> listener) {
        instanceCache.addListener(listener);
    }

    public CompletableFuture<CommonResponseBody> requestAsync(byte type, Object body) {
        return requestAsync(type, body, AckMode.SINGLE, options.getRequestTimeoutMs());
    }

    public CompletableFuture<CommonResponseBody> requestAsync(
            byte type, Object body, AckMode ackMode, int timeoutMs) {
        ensureStarted();
        Channel current = requireActiveChannel();
        long requestId = requestIdGenerator.next();
        CompletableFuture<CommonResponseBody> future = pendingRequests.create(requestId, timeoutMs);
        RoverMessage message = RoverMessageCodecSupport.request(type, requestId, ackMode, timeoutMs, body);
        log.debug("发送请求, type={}, requestId={}, timeoutMs={}",
                ProtocolTypeNames.nameOf(type), requestId, timeoutMs);
        current.writeAndFlush(message).addListener(writeFuture -> {
            if (!writeFuture.isSuccess()) {
                log.warn("请求写出失败, type={}, requestId={}",
                        ProtocolTypeNames.nameOf(type), requestId, writeFuture.cause());
                pendingRequests.fail(requestId, writeFuture.cause() == null
                        ? new IllegalStateException("写入失败")
                        : writeFuture.cause());
            }
        });
        return future;
    }

    public CommonResponseBody requestSync(byte type, Object body) {
        try {
            return requestAsync(type, body).get(options.getRequestTimeoutMs() + SYNC_GRACE_MILLIS, TimeUnit.MILLISECONDS);
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

    public boolean onDisconnected(Channel disconnectedChannel) {
        return channelState.deactivate(
                disconnectedChannel,
                () -> pendingRequests.failAll(new IllegalStateException("连接已断开")));
    }

    @Deprecated(forRemoval = false)
    public void onDisconnected() {
        Channel current = channelState.current();
        if (current != null) {
            onDisconnected(current);
        }
    }

    private void connect() {
        Channel connectedChannel = null;
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
                                    .addLast(new NameserverClientHandler(
                                            pendingRequests, instanceCache, NameserverClient.this));
                        }
                    });

            ChannelFuture future = bootstrap.connect(options.getHost(), options.getPort()).sync();
            connectedChannel = future.channel();
            if (!channelState.activate(connectedChannel)) {
                connectedChannel.close();
                if (started.get()) {
                    log.warn("Nameserver 连接在可用前已断开，将按固定周期重试: {}:{}",
                            options.getHost(), options.getPort());
                }
                return;
            }
            log.info("已连接 Nameserver: {}:{}", options.getHost(), options.getPort());
            recoverState();
        } catch (Exception ex) {
            if (connectedChannel != null) {
                channelState.deactivate(
                        connectedChannel,
                        () -> pendingRequests.failAll(new IllegalStateException("连接已断开")));
                connectedChannel.close();
            }
            log.warn("连接 Nameserver 失败: {}:{}", options.getHost(), options.getPort(), ex);
        }
    }

    private void tryReconnect() {
        if (!started.get() || !options.isAutoReconnect()) {
            return;
        }
        if (isActive()) {
            recoverPendingState();
            return;
        }
        log.info("尝试重连 Nameserver: {}:{}", options.getHost(), options.getPort());
        connect();
    }

    /** 供测试与重连路径调用：标记待恢复并重放。 */
    void recoverState() {
        stateStore.recoverState(syncCaller);
    }

    void recoverPendingState() {
        stateStore.recoverPendingState(syncCaller);
    }

    void heartbeatRegistered() {
        stateStore.heartbeatRegistered(syncCaller);
    }

    private Channel requireActiveChannel() {
        Channel current = channelState.current();
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

    @Override
    public void close() {
        shutdown();
    }

    public void shutdown() {
        if (!started.compareAndSet(true, false)) {
            return;
        }
        Channel current = channelState.stopAcceptingConnections();
        if (heartbeatTask != null) {
            heartbeatTask.stop();
        }
        if (reconnectTask != null) {
            reconnectTask.stop();
        }
        pendingRequests.failAll(new IllegalStateException("客户端已关闭"));
        pendingRequests.close();
        if (current != null) {
            current.close();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
        stateStore.clearAll();
        instanceCache.clear();
        log.info("NameserverClient 已关闭");
    }
}
