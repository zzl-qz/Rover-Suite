package com.rover.nameserver.core.push;

import com.rover.common.protocol.ServicePushBody;
import com.rover.nameserver.client.codec.RoverMessageCodecSupport;
import com.rover.nameserver.core.registry.RegistrySnapshot;
import io.netty.channel.Channel;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;

/**
 * Author: Daylight
 * Created: 2026-08-08 17:50:00
 * Description: 服务变更推送
 *
 * 核心职责：在注册表发生变更（注册/注销/过期剔除）后，把本次变更后的
 * 服务快照（SNAPSHOT 类型，随包携带 serviceName/group/revision 与全量实例列表）
 * 推送给所有相关订阅连接，让客户端免轮询地感知服务变化。</p>
 *
 * 被哪些组件使用：{@link com.rover.nameserver.core.server.NameserverRequestDispatcher}
 * 在注册/注销、订阅建立时调用；{@link com.rover.nameserver.core.health.HealthChecker}
 * 在剔除过期临时实例后调用。</p>
 *
 * 去重与 revision 机制：每张快照携带注册表为服务维护的自增 revision
 * （见 {@link com.rover.nameserver.core.registry.InMemoryServiceRegistry} 的
 * bumpRevision），客户端以 revision 判断是否比本地新，从而丢弃重复/过期推送；
 * 推送本身不在此做业务去重，只通过 pushId 区分单次发送。</p>
 *
 * 兄弟类 {@link SubscriptionManager} 负责订阅关系查询与失效连接清理，
 * 本类只负责「对谁推、推什么」。</p>
 */
@Slf4j
public class PushService {

    /** 订阅关系管理器：按 serviceName/group 查询目标连接 */
    private final SubscriptionManager subscriptionManager;
    /** 推送总开关，支持运行时热更新（nameserver.push.enabled） */
    private final AtomicBoolean pushEnabled;
    /** 全局推送序号发生器，从 1 递增，仅用于区分单次推送（客户端可忽略） */
    private final AtomicLong pushIdGenerator = new AtomicLong(1);

    /**
     * 构造推送服务。
     *
     * @param subscriptionManager 订阅关系管理器
     * @param pushEnabled         初始推送开关状态
     */
    public PushService(SubscriptionManager subscriptionManager, boolean pushEnabled) {
        this.subscriptionManager = subscriptionManager;
        this.pushEnabled = new AtomicBoolean(pushEnabled);
    }

    public boolean isPushEnabled() {
        return pushEnabled.get();
    }

    /** 运行时开关推送能力（热更新入口） */
    public void setPushEnabled(boolean enabled) {
        this.pushEnabled.set(enabled);
    }

    /**
     * 推送一份变更快照给相关订阅者。
     *
     * @param snapshot 变更后的服务快照（含最新 revision 与实例全量）
     *                 关闭推送或快照为 null 或无人订阅时直接跳过
     */
    public void pushSnapshot(RegistrySnapshot snapshot) {
        if (!pushEnabled.get() || snapshot == null) {
            return;
        }
        // 按服务名+组查找订阅者；findSubscribers 已做去重（ConcurrentHashMap.newKeySet）
        Set<Channel> subscribers =
                subscriptionManager.findSubscribers(snapshot.getServiceName(), snapshot.getGroup());
        if (subscribers.isEmpty()) {
            return;
        }

        // 组装推送体：全量实例 + 本次 revision，客户端据此做增量/去重
        ServicePushBody body = new ServicePushBody();
        body.setServiceName(snapshot.getServiceName());
        body.setGroup(snapshot.getGroup());
        body.setInstances(snapshot.getInstances());
        body.setRevision(snapshot.getRevision());
        body.setPushType("SNAPSHOT");

        long pushId = pushIdGenerator.getAndIncrement();
        for (Channel channel : subscribers) {
            if (channel == null) {
                continue;
            }
            // 连接已失效的订阅者就地清理，避免反复向死连接发送
            if (!channel.isActive()) {
                subscriptionManager.removeChannel(channel);
                continue;
            }
            channel.writeAndFlush(RoverMessageCodecSupport.push(pushId, body)).addListener(future -> {
                // 单次发送失败的异步回调：记录日志，不影响其他订阅者的推送
                if (!future.isSuccess()) {
                    log.warn("推送失败: service={}, channel={}",
                            snapshot.getServiceName(), channel.remoteAddress(), future.cause());
                }
            });
        }
        log.info("推送服务变更: service={}, revision={}, subscribers={}",
                snapshot.getServiceName(), snapshot.getRevision(), subscribers.size());
    }
}