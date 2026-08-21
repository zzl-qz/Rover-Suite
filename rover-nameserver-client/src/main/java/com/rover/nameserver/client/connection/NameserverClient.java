package com.rover.nameserver.client.connection;

import com.rover.common.concurrent.PendingRequestTable;
import com.rover.common.constants.ProtocolTypeNames;
import com.rover.common.concurrent.PeriodicTask;
import com.rover.common.concurrent.RequestIdGenerator;
import com.rover.common.constants.ProtocolConstants;
import com.rover.common.constants.StatusConstants;
import com.rover.common.util.ServiceKeys;
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
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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

    /** 异步转同步时额外增加的等待时间，覆盖调度误差（毫秒）。 */
    private static final long SYNC_GRACE_MILLIS = 1000;
    /** 按实例/订阅键串行生命周期操作，避免恢复请求与显式删除交叉。 */
    private static final int STATE_LOCK_STRIPES = 64;

    /** 客户端配置 */
    @Getter
    private final NameserverClientOptions options;
    /** 本地实例缓存 */
    @Getter
    private final InstanceCache instanceCache = new InstanceCache();
    /** requestId 生成器 */
    private final RequestIdGenerator requestIdGenerator = new RequestIdGenerator();
    /** 在途请求表 */
    private final PendingRequestTable<CommonResponseBody> pendingRequests;
    /** 已注册实例，重连恢复用 */
    private final Map<String, RegisterRequest> registeredInstances = new ConcurrentHashMap<>();
    /** 已订阅关系，重连恢复用 */
    private final Map<String, SubscribeRequest> subscriptions = new ConcurrentHashMap<>();
    /** 等待固定周期重试的注册恢复项 */
    private final Set<String> pendingRegistrationRecoveries = ConcurrentHashMap.newKeySet();
    /** 等待固定周期重试的订阅恢复项 */
    private final Set<String> pendingSubscriptionRecoveries = ConcurrentHashMap.newKeySet();
    /** 正在恢复的注册项，避免心跳与重连任务并发重放同一实例 */
    private final Set<String> recoveringRegistrations = ConcurrentHashMap.newKeySet();
    /** 正在恢复的订阅项，避免重复重放同一订阅 */
    private final Set<String> recoveringSubscriptions = ConcurrentHashMap.newKeySet();
    /** 有界条带锁，不随动态 service key 增长。 */
    private final Object[] stateLocks = createStateLocks();
    /** 是否已 start */
    private final AtomicBoolean started = new AtomicBoolean(false);
    /** 当前连接的原子发布/摘除状态，按 channel 身份隔离新旧连接回调。 */
    private final ClientChannelState channelState = new ClientChannelState();
    /** Netty 工作线程组 */
    private EventLoopGroup workerGroup;
    /** 心跳定时任务 */
    private PeriodicTask heartbeatTask;
    /** 重连定时任务 */
    private PeriodicTask reconnectTask;

    /**
     * 构造客户端，仅保存配置与初始化在途请求表，真正的连接在 start 中建立。
     *
     * @param options 客户端配置（地址、超时、心跳/重连开关等）；不能为 null
     */
    public NameserverClient(NameserverClientOptions options) {
        this.options = Objects.requireNonNull(options, "options");
        this.pendingRequests = new PendingRequestTable<>(options.getMaxPendingRequests());
    }

    /**
     * 启动客户端：幂等，仅首次调用生效。建立连接，并按配置启动心跳定时任务
     * 与重连定时任务。
     */
    public void start() {
        // CAS 保证只启动一次，重复调用直接返回
        if (!started.compareAndSet(false, true)) {
            return;
        }
        channelState.startAcceptingConnections();
        workerGroup = new NioEventLoopGroup();
        connect();
        if (options.isAutoHeartbeat()) {
            heartbeatTask = new PeriodicTask("nameserver-client-heartbeat");
            heartbeatTask.start(this::heartbeatRegistered, options.getHeartbeatIntervalMs(), options.getHeartbeatIntervalMs());
        }
        // 开启重连定时任务
        if (options.isAutoReconnect()) {
            reconnectTask = new PeriodicTask("nameserver-client-reconnect");
            // 周期拉活：连接还活着就跳过；断了则执行 connect 重连
            reconnectTask.start(this::tryReconnect, options.getReconnectIntervalMs(), options.getReconnectIntervalMs());
        }
    }

    /** 当前 TCP 连接是否可用（已建立且 active）。 */
    public boolean isActive() {
        return channelState.isActive();
    }

    /**
     * 注册实例（同步）。成功后把注册信息备份到本地，供断线重连后自动补注册。
     *
     * @param request 注册请求（服务名、实例 id、地址、权重等）
     * @return 注册响应；服务端返回非成功码时抛 RoverException
     */
    public CommonResponseBody register(RegisterRequest request) {
        String key = instanceKey(request.getServiceName(), request.getInstanceId());
        synchronized (stateLock("instance:" + key)) {
            request.setToken(options.getToken());
            CommonResponseBody response = requestSync(ProtocolConstants.REGISTER_REQUEST, request);
            ensureSuccess(response, "注册失败");
            // 本地也记一份，断线重连后才能自动补注册
            registeredInstances.put(key, copyRegister(request));
            pendingRegistrationRecoveries.remove(key);
            return response;
        }
    }

    /**
     * 注销实例（同步）。成功后移除本地注册备份。
     *
     * @param serviceName 服务名
     * @param instanceId  实例 id
     * @return 注销响应；服务端返回非成功码时抛 RoverException
     */
    public CommonResponseBody unregister(String serviceName, String instanceId) {
        String key = instanceKey(serviceName, instanceId);
        synchronized (stateLock("instance:" + key)) {
            UnregisterRequest request = new UnregisterRequest();
            request.setServiceName(serviceName);
            request.setInstanceId(instanceId);
            request.setToken(options.getToken());
            CommonResponseBody response = requestSync(ProtocolConstants.UNREGISTER_REQUEST, request);
            ensureSuccess(response, "注销失败");
            // 确认服务端注销成功后再清本地备份；同 key 的恢复请求不能越过本操作。
            registeredInstances.remove(key);
            pendingRegistrationRecoveries.remove(key);
            return response;
        }
    }

    /**
     * 对指定实例发送心跳（同步）。
     *
     * @param serviceName 服务名
     * @param instanceId  实例 id
     * @return 心跳响应
     */
    public CommonResponseBody heartbeat(String serviceName, String instanceId) {
        HeartbeatRequest request = new HeartbeatRequest();
        request.setServiceName(serviceName);
        request.setInstanceId(instanceId);
        request.setClientTimeMillis(System.currentTimeMillis());
        request.setToken(options.getToken());
        return requestSync(ProtocolConstants.HEARTBEAT_REQUEST, request);
    }

    /**
     * 查询服务实例（同步）。成功后把返回的快照写入本地缓存。
     *
     * @param serviceName 服务名
     * @param group       分组名，可为 null
     * @param healthyOnly 是否只返回健康实例
     * @return 查询结果体（含实例列表与版本号）
     */
    public QueryResponseBody query(String serviceName, String group, boolean healthyOnly) {
        QueryRequest request = new QueryRequest();
        request.setServiceName(serviceName);
        request.setGroup(group);
        request.setHealthyOnly(healthyOnly);
        request.setToken(options.getToken());

        CommonResponseBody response = requestSync(ProtocolConstants.QUERY_REQUEST, request);
        ensureSuccess(response, "查询失败");
        // 查询结果解码后落缓存：对账路径以服务端为准全量覆盖
        QueryResponseBody body = ProtostuffSerializer.deserialize(response.getData(), QueryResponseBody.class);
        String epoch = body.getEpoch() != null && !body.getEpoch().isBlank()
                ? body.getEpoch()
                : response.getEpoch();
        instanceCache.putSnapshotFromQuery(
                serviceName, group, epoch, body.getRevision(), body.getInstances());
        return body;
    }

    /**
     * 读取本地缓存的实例列表（不发起网络请求）。
     *
     * @param serviceName 服务名
     * @param group       分组名，可为 null
     * @return 缓存中的实例列表，无缓存时为空列表
     */
    public List<ServiceInstance> getCachedInstances(String serviceName, String group) {
        return instanceCache.get(serviceName, group);
    }

    /**
     * 订阅服务变更（同步）。成功后保存订阅关系，供重连后自动恢复；后续服务端
     * 推送会通过已注册的 push 监听器送达。
     *
     * @param serviceName 服务名
     * @param group       分组名，可为 null
     * @return 订阅响应；服务端返回非成功码时抛 RoverException
     */
    public CommonResponseBody subscribe(String serviceName, String group) {
        String key = subscribeKey(serviceName, group);
        synchronized (stateLock("subscription:" + key)) {
            SubscribeRequest request = new SubscribeRequest();
            request.setServiceName(serviceName);
            request.setGroup(group);
            // 带上本地已知版本号，支持增量订阅
            request.setKnownRevision(instanceCache.revision(serviceName, group));
            request.setToken(options.getToken());

            CommonResponseBody response = requestSync(ProtocolConstants.SUBSCRIBE_REQUEST, request);
            ensureSuccess(response, "订阅失败");
            subscriptions.put(key, request);
            pendingSubscriptionRecoveries.remove(key);
            return response;
        }
    }

    /**
     * 取消订阅（同步）。成功后移除本地订阅记录。
     *
     * @param serviceName 服务名
     * @param group       分组名，可为 null
     * @return 取消订阅响应；服务端返回非成功码时抛 RoverException
     */
    public CommonResponseBody unsubscribe(String serviceName, String group) {
        String key = subscribeKey(serviceName, group);
        synchronized (stateLock("subscription:" + key)) {
            UnsubscribeRequest request = new UnsubscribeRequest();
            request.setServiceName(serviceName);
            request.setGroup(group);
            request.setToken(options.getToken());
            CommonResponseBody response = requestSync(ProtocolConstants.UNSUBSCRIBE_REQUEST, request);
            ensureSuccess(response, "取消订阅失败");
            subscriptions.remove(key);
            pendingSubscriptionRecoveries.remove(key);
            return response;
        }
    }

    /**
     * 注册服务端推送监听器，订阅变更（含服务端主动推送）会回调该监听器。
     *
     * @DL 扩展 API：Gateway 内部直接读取缓存；外部客户端可用它接收已应用的推送。
     * @param listener 推送回调；为 null 时忽略
     */
    public void addPushListener(Consumer<ServicePushBody> listener) {
        instanceCache.addListener(listener);
    }

    /**
     * 异步发送请求，返回的 Future 会在响应到达（或超时/失败）时完成。
     * 使用默认 ack 模式（SINGLE）与默认请求超时。
     *
     * @param type 请求消息类型（ProtocolConstants 常量）
     * @param body 请求体对象
     * @return 关联响应的 Future；响应通过 requestId 配对后由 handler complete
     */
    public CompletableFuture<CommonResponseBody> requestAsync(byte type, Object body) {
        return requestAsync(type, body, AckMode.SINGLE, options.getRequestTimeoutMs());
    }

    /**
     * 异步发送请求（可指定 ack 模式与超时）。请求配对流程：生成 requestId ->
     * 先在 PendingRequestTable 登记 Future（带超时）-> 组装并写出消息；响应到达时
     * handler 按 requestId complete 对应 Future，写失败或超时则 fail。
     *
     * @param type      请求消息类型
     * @param body      请求体对象
     * @param ackMode   ack 模式
     * @param timeoutMs 等待响应的超时（毫秒）
     * @return 关联响应的 Future；超时/写入失败/连接断开时以异常完成
     */
    public CompletableFuture<CommonResponseBody> requestAsync(
            byte type, Object body, AckMode ackMode, int timeoutMs) {
        ensureStarted();
        Channel current = requireActiveChannel();
        long requestId = requestIdGenerator.next();
        // 先挂 pending 再写出，避免响应太快对不上号
        CompletableFuture<CommonResponseBody> future = pendingRequests.create(requestId, timeoutMs);
        RoverMessage message = RoverMessageCodecSupport.request(type, requestId, ackMode, timeoutMs, body);
        log.debug("发送请求, type={}, requestId={}, timeoutMs={}",
                ProtocolTypeNames.nameOf(type), requestId, timeoutMs);
        // 写完成回调里检查写结果，失败立刻 fail，避免调用方干等超时
        current.writeAndFlush(message).addListener(writeFuture -> {
            if (!writeFuture.isSuccess()) {
                log.warn("请求写出失败, type={}, requestId={}",
                        ProtocolTypeNames.nameOf(type), requestId, writeFuture.cause());
                // 没写出成功就别让调用方干等到超时
                pendingRequests.fail(requestId, writeFuture.cause() == null
                        ? new IllegalStateException("写入失败")
                        : writeFuture.cause());
            }
        });
        return future;
    }

    /**
     * 异步转同步：堵住当前线程，等 Future 被响应 complete
     * （额外多等 1 秒覆盖调度误差）。
     *
     * @param type 请求消息类型
     * @param body 请求体对象
     * @return 同步等待到的响应体
     * @throws RoverException 超时、被中断或执行异常（运行时异常原样抛出）时抛出
     */
    public CommonResponseBody requestSync(byte type, Object body) {
        try {
            return requestAsync(type, body).get(options.getRequestTimeoutMs() + SYNC_GRACE_MILLIS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            throw new RoverException("请求超时", ex);
        } catch (InterruptedException ex) {
            // 恢复中断标志，避免吞掉中断
            Thread.currentThread().interrupt();
            throw new RoverException("请求被中断", ex);
        } catch (ExecutionException ex) {
            // ExecutionException 只是包装，把真实原因解出来抛给调用方
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new RoverException(cause.getMessage(), cause);
        }
    }

    /**
     * 通道断开回调（由 handler 在 channelInactive 中触发）：
     * 仅在回调 channel 仍是当前连接时摘除并失败在途请求；真正重连交给后台任务，
     * 避免在 Netty IO 线程里同步连接。
     */
    public boolean onDisconnected(Channel disconnectedChannel) {
        return channelState.deactivate(
                disconnectedChannel,
                () -> pendingRequests.failAll(new IllegalStateException("连接已断开")));
    }

    /**
     * 兼容旧调用方的无参断开通知。新代码应传入实际断开的 channel，才能完整隔离
     * 新旧连接回调；这里读取快照后仍通过身份校验摘除，不会误清随后建立的新连接。
     */
    @Deprecated(forRemoval = false)
    public void onDisconnected() {
        Channel current = channelState.current();
        if (current != null) {
            onDisconnected(current);
        }
    }

    /**
     * 建立到 Nameserver 的 TCP 连接。仅发布仍 active 的连接，成功后重放本地状态；
     * 失败或连接过早断开时等待 reconnectTask 下次固定周期尝试。
     */
    private void connect() {
        Channel connectedChannel = null;
        try {
            // 每次重连都新建 Bootstrap，workerGroup 全局复用
            Bootstrap bootstrap = new Bootstrap();
            bootstrap.group(workerGroup)
                    .channel(NioSocketChannel.class)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, options.getConnectTimeoutMs())
                    .option(ChannelOption.SO_KEEPALIVE, true)
                    .option(ChannelOption.TCP_NODELAY, true)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            // 流水线：解码 -> 编码 -> 业务收包处理（响应配对/推送落缓存）
                            ch.pipeline()
                                    .addLast(new RoverMessageDecoder())
                                    .addLast(new RoverMessageEncoder())
                                    .addLast(new NameserverClientHandler(pendingRequests, instanceCache, NameserverClient.this));
                        }
                    });

            // 同步等待连接建立完成
            ChannelFuture future = bootstrap.connect(options.getHost(), options.getPort()).sync();
            connectedChannel = future.channel();
            // accept 后立即 close 时，channelInactive 可能早于 sync 返回。只发布仍 active 的连接，
            // 避免把已失活 channel 写回并让后续重连永久停住。
            if (!channelState.activate(connectedChannel)) {
                connectedChannel.close();
                if (started.get()) {
                    log.warn("Nameserver 连接在可用前已断开，将按固定周期重试: {}:{}",
                            options.getHost(), options.getPort());
                }
                return;
            }
            log.info("已连接 Nameserver: {}:{}", options.getHost(), options.getPort());
            // 新连接上重放注册/订阅状态
            recoverState();
        } catch (Exception ex) {
            // 只清理由本次 connect 发布的 channel；旧连接的失败不能覆盖较新的连接。
            if (connectedChannel != null) {
                channelState.deactivate(
                        connectedChannel,
                        () -> pendingRequests.failAll(new IllegalStateException("连接已断开")));
                connectedChannel.close();
            }
            log.warn("连接 Nameserver 失败: {}:{}", options.getHost(), options.getPort(), ex);
        }
    }

    /**
     * 周期重连任务：客户端已启动且开了自动重连时才动作；当前活连接存在时继续重试
     * 尚未恢复成功的状态，否则发起 connect。即使出现未及时摘除的 inactive 引用，
     * 也不能阻止下一次连接尝试。
     */
    private void tryReconnect() {
        if (!started.get() || !options.isAutoReconnect()) {
            return;
        }
        if (isActive()) {
            // 首次恢复的业务响应可能失败，沿用现有固定重连周期继续补偿
            recoverPendingState();
            return;
        }
        log.info("尝试重连 Nameserver: {}:{}", options.getHost(), options.getPort());
        connect();
    }

    /**
     * 重连成功后：服务端可能已清实例，按本地记录补注册/订阅
     * （注册与订阅逐个重放，单个失败不影响其余）。
     */
    void recoverState() {
        pendingRegistrationRecoveries.addAll(registeredInstances.keySet());
        pendingSubscriptionRecoveries.addAll(subscriptions.keySet());
        recoverPendingState();
    }

    /**
     * 重试尚未恢复成功的注册与订阅。独立的执行中集合保证心跳线程与重连线程不会
     * 对同一项并发重放；失败项仍留在待恢复集合中，等待下一个固定周期。
     */
    void recoverPendingState() {
        for (String key : pendingRegistrationRecoveries) {
            recoverRegistration(key);
        }
        for (String key : pendingSubscriptionRecoveries) {
            recoverSubscription(key);
        }
    }

    private void recoverRegistration(String key) {
        synchronized (stateLock("instance:" + key)) {
            if (!pendingRegistrationRecoveries.contains(key) || !recoveringRegistrations.add(key)) {
                return;
            }
            RegisterRequest request = registeredInstances.get(key);
            try {
                if (request == null) {
                    pendingRegistrationRecoveries.remove(key);
                    return;
                }
                CommonResponseBody response = requestSync(ProtocolConstants.REGISTER_REQUEST, request);
                ensureSuccess(response, "恢复注册失败");
                if (registeredInstances.get(key) == request) {
                    pendingRegistrationRecoveries.remove(key);
                }
            } catch (Exception ex) {
                if (registeredInstances.get(key) == request) {
                    pendingRegistrationRecoveries.add(key);
                }
                if (request != null) {
                    log.warn("恢复注册失败，将按固定周期重试: {}#{}",
                            request.getServiceName(), request.getInstanceId(), ex);
                }
            } finally {
                recoveringRegistrations.remove(key);
            }
        }
    }

    private void recoverSubscription(String key) {
        synchronized (stateLock("subscription:" + key)) {
            if (!pendingSubscriptionRecoveries.contains(key) || !recoveringSubscriptions.add(key)) {
                return;
            }
            SubscribeRequest request = subscriptions.get(key);
            try {
                if (request == null) {
                    pendingSubscriptionRecoveries.remove(key);
                    return;
                }
                CommonResponseBody response = requestSync(ProtocolConstants.SUBSCRIBE_REQUEST, request);
                ensureSuccess(response, "恢复订阅失败");
                if (subscriptions.get(key) == request) {
                    pendingSubscriptionRecoveries.remove(key);
                }
            } catch (Exception ex) {
                if (subscriptions.get(key) == request) {
                    pendingSubscriptionRecoveries.add(key);
                }
                if (request != null) {
                    log.warn("恢复订阅失败，将按固定周期重试: {}", request.getServiceName(), ex);
                }
            } finally {
                recoveringSubscriptions.remove(key);
            }
        }
    }

    /**
     * 定时给本客户端注册过的实例打心跳（心跳续约）
     * 由 heartbeatTask 按 heartbeatIntervalMs 周期执行。
     */
    void heartbeatRegistered() {
        if (!isActive() || registeredInstances.isEmpty()) {
            return;
        }
        // 对所有本地注册备份逐个续约；同 key 与 register/unregister/recovery 串行。
        for (RegisterRequest request : registeredInstances.values()) {
            String key = instanceKey(request.getServiceName(), request.getInstanceId());
            synchronized (stateLock("instance:" + key)) {
                if (registeredInstances.get(key) != request) {
                    continue;
                }
                if (pendingRegistrationRecoveries.contains(key)) {
                    recoverRegistration(key);
                    continue;
                }
                try {
                    CommonResponseBody response = heartbeat(request.getServiceName(), request.getInstanceId());
                    if (response != null && response.getCode() == StatusConstants.SERVICE_NOT_FOUND) {
                        log.info("心跳发现实例不存在，立即重新注册: {}#{}",
                                request.getServiceName(), request.getInstanceId());
                        pendingRegistrationRecoveries.add(key);
                        recoverRegistration(key);
                        continue;
                    }
                    if (response != null && response.getCode() == StatusConstants.CONFLICT) {
                        // 相同实例已由其他进程/连接接管。旧 owner 必须停止续约，避免重连后反复争抢。
                        registeredInstances.remove(key, request);
                        pendingRegistrationRecoveries.remove(key);
                        log.error("实例已由其他会话接管，停止本连接续约: {}#{}",
                                request.getServiceName(), request.getInstanceId());
                        continue;
                    }
                    if (response == null || response.getCode() != StatusConstants.SUCCESS) {
                        log.warn("心跳失败: {}#{}, code={}, msg={}",
                                request.getServiceName(),
                                request.getInstanceId(),
                                response == null ? -1 : response.getCode(),
                                response == null ? "空响应" : response.getMessage());
                    }
                } catch (Exception ex) {
                    log.warn("发送心跳异常: {}#{}", request.getServiceName(), request.getInstanceId(), ex);
                }
            }
        }
    }

    /**
     * 取当前活跃通道，不可用时抛异常。
     *
     * @return 当前连接的 Channel
     * @throws RoverException 未连接或连接已断开时抛出
     */
    private Channel requireActiveChannel() {
        Channel current = channelState.current();
        if (current == null || !current.isActive()) {
            throw new RoverException("尚未连接到 Nameserver");
        }
        return current;
    }

    /** 校验客户端已 start，否则抛异常。 */
    private void ensureStarted() {
        if (!started.get()) {
            throw new RoverException("NameserverClient 尚未 start");
        }
    }

    /**
     * 校验响应为成功码，否则抛异常。
     *
     * @param response 服务端响应；为 null 时视为失败
     * @param prefix   异常描述前缀（如"注册失败"）
     * @throws RoverException 响应为 null 或 code 非成功时抛出
     */
    private void ensureSuccess(CommonResponseBody response, String prefix) {
        if (response == null) {
            throw new RoverException(prefix + ": 空响应");
        }
        if (response.getCode() != StatusConstants.SUCCESS) {
            throw new RoverException(prefix + ": code=" + response.getCode() + ", message=" + response.getMessage());
        }
    }

    /** 拼接注册备份键：serviceName#instanceId。 */
    private String instanceKey(String serviceName, String instanceId) {
        return ServiceKeys.serviceInstance(serviceName, instanceId);
    }

    /** 拼接订阅备份键：serviceName#group（group 为 null 视为空串）。 */
    private String subscribeKey(String serviceName, String group) {
        return ServiceKeys.serviceGroup(serviceName, group);
    }

    private Object stateLock(String key) {
        int index = Math.floorMod(key == null ? 0 : key.hashCode(), STATE_LOCK_STRIPES);
        return stateLocks[index];
    }

    private static Object[] createStateLocks() {
        Object[] locks = new Object[STATE_LOCK_STRIPES];
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new Object();
        }
        return locks;
    }

    /**
     * 深拷贝注册请求：本地备份必须持有独立对象，避免调用方后续修改原请求污染备份。
     */
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
        copy.setToken(source.getToken());
        copy.setMetadata(source.getMetadata() == null
                ? new java.util.HashMap<>()
                : new java.util.HashMap<>(source.getMetadata()));
        return copy;
    }

    /** AutoCloseable 支持，等价于 shutdown()。 */
    @Override
    public void close() {
        shutdown();
    }

    /**
     * 关闭客户端：幂等。按"停定时任务 -> 失败的请求 -> 关连接 -> 释放线程池
     * -> 清本地状态"的顺序释放资源，保证不再有新的心跳/重连/请求动作，
     * 在途请求都以"客户端已关闭"快速失败。
     */
    public void shutdown() {
        if (!started.compareAndSet(true, false)) {
            return;
        }
        // 先禁止发布晚到的连接成功结果，并摘除当前连接；实际 close 放在在途请求失败之后。
        Channel current = channelState.stopAcceptingConnections();
        // 先停定时任务，避免关闭过程中心跳/重连还在拉活
        if (heartbeatTask != null) {
            heartbeatTask.stop();
        }
        if (reconnectTask != null) {
            reconnectTask.stop();
        }
        // 在途请求全部快速失败，让等待中的业务线程立刻返回而不是空等超时
        pendingRequests.failAll(new IllegalStateException("客户端已关闭"));
        pendingRequests.close();
        // 再关连接、释放 worker 线程池
        if (current != null) {
            current.close();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
        // 最后清空本地状态
        registeredInstances.clear();
        subscriptions.clear();
        pendingRegistrationRecoveries.clear();
        pendingSubscriptionRecoveries.clear();
        recoveringRegistrations.clear();
        recoveringSubscriptions.clear();
        instanceCache.clear();
        log.info("NameserverClient 已关闭");
    }
}
