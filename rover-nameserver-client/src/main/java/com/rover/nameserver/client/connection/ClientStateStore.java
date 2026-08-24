package com.rover.nameserver.client.connection;

import com.rover.common.constants.ProtocolConstants;
import com.rover.common.constants.StatusConstants;
import com.rover.common.protocol.CommonResponseBody;
import com.rover.common.protocol.RegisterRequest;
import com.rover.common.protocol.SubscribeRequest;
import com.rover.common.util.ServiceKeys;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;

/**
 * Author: Daylight
 * Description: 客户端本地注册/订阅状态与断线恢复。
 * 条带锁串行同 key 的 register/unregister/recover/heartbeat，避免交叉。
 */
@Slf4j
final class ClientStateStore {

    private static final int STATE_LOCK_STRIPES = 64;

    /** 向 Nameserver 发同步请求的窄接口，避免 Store 直接依赖整颗 Client。 */
    interface SyncCaller {
        boolean isActive();

        CommonResponseBody requestSync(byte type, Object body);

        CommonResponseBody heartbeat(String serviceName, String instanceId);
    }

    private final Map<String, RegisterRequest> registeredInstances = new ConcurrentHashMap<>();
    private final Map<String, SubscribeRequest> subscriptions = new ConcurrentHashMap<>();
    private final Set<String> pendingRegistrationRecoveries = ConcurrentHashMap.newKeySet();
    private final Set<String> pendingSubscriptionRecoveries = ConcurrentHashMap.newKeySet();
    private final Set<String> recoveringRegistrations = ConcurrentHashMap.newKeySet();
    private final Set<String> recoveringSubscriptions = ConcurrentHashMap.newKeySet();
    private final Object[] stateLocks = createStateLocks();

    Object instanceLock(String serviceName, String instanceId) {
        return stateLock("instance:" + instanceKey(serviceName, instanceId));
    }

    Object subscriptionLock(String serviceName, String group) {
        return stateLock("subscription:" + subscribeKey(serviceName, group));
    }

    void rememberRegistered(RegisterRequest request) {
        String key = instanceKey(request.getServiceName(), request.getInstanceId());
        registeredInstances.put(key, copyRegister(request));
        pendingRegistrationRecoveries.remove(key);
    }

    void forgetRegistered(String serviceName, String instanceId) {
        String key = instanceKey(serviceName, instanceId);
        registeredInstances.remove(key);
        pendingRegistrationRecoveries.remove(key);
    }

    void rememberSubscribed(SubscribeRequest request) {
        String key = subscribeKey(request.getServiceName(), request.getGroup());
        subscriptions.put(key, request);
        pendingSubscriptionRecoveries.remove(key);
    }

    void forgetSubscribed(String serviceName, String group) {
        String key = subscribeKey(serviceName, group);
        subscriptions.remove(key);
        pendingSubscriptionRecoveries.remove(key);
    }

    void clearAll() {
        registeredInstances.clear();
        subscriptions.clear();
        pendingRegistrationRecoveries.clear();
        pendingSubscriptionRecoveries.clear();
        recoveringRegistrations.clear();
        recoveringSubscriptions.clear();
    }

    /** 重连成功后：把本地备份全部标成待恢复并立刻尝试一轮。 */
    void recoverState(SyncCaller caller) {
        pendingRegistrationRecoveries.addAll(registeredInstances.keySet());
        pendingSubscriptionRecoveries.addAll(subscriptions.keySet());
        recoverPendingState(caller);
    }

    void recoverPendingState(SyncCaller caller) {
        for (String key : pendingRegistrationRecoveries) {
            recoverRegistration(key, caller);
        }
        for (String key : pendingSubscriptionRecoveries) {
            recoverSubscription(key, caller);
        }
    }

    /** 定时给已注册实例续约；发现 NOT_FOUND 立即补注册。 */
    void heartbeatRegistered(SyncCaller caller) {
        if (!caller.isActive() || registeredInstances.isEmpty()) {
            return;
        }
        for (RegisterRequest request : registeredInstances.values()) {
            String key = instanceKey(request.getServiceName(), request.getInstanceId());
            synchronized (stateLock("instance:" + key)) {
                if (registeredInstances.get(key) != request) {
                    continue;
                }
                if (pendingRegistrationRecoveries.contains(key)) {
                    recoverRegistration(key, caller);
                    continue;
                }
                try {
                    CommonResponseBody response = caller.heartbeat(
                            request.getServiceName(), request.getInstanceId());
                    if (response != null && response.getCode() == StatusConstants.SERVICE_NOT_FOUND) {
                        log.info("心跳发现实例不存在，立即重新注册: {}#{}",
                                request.getServiceName(), request.getInstanceId());
                        pendingRegistrationRecoveries.add(key);
                        recoverRegistration(key, caller);
                        continue;
                    }
                    if (response != null && response.getCode() == StatusConstants.CONFLICT) {
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

    private void recoverRegistration(String key, SyncCaller caller) {
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
                CommonResponseBody response = caller.requestSync(ProtocolConstants.REGISTER_REQUEST, request);
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

    private void recoverSubscription(String key, SyncCaller caller) {
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
                CommonResponseBody response = caller.requestSync(ProtocolConstants.SUBSCRIBE_REQUEST, request);
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

    private static void ensureSuccess(CommonResponseBody response, String prefix) {
        if (response == null) {
            throw new com.rover.common.exception.RoverException(prefix + ": 空响应");
        }
        if (response.getCode() != StatusConstants.SUCCESS) {
            throw new com.rover.common.exception.RoverException(
                    prefix + ": code=" + response.getCode() + ", message=" + response.getMessage());
        }
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

    static String instanceKey(String serviceName, String instanceId) {
        return ServiceKeys.serviceInstance(serviceName, instanceId);
    }

    static String subscribeKey(String serviceName, String group) {
        return ServiceKeys.serviceGroup(serviceName, group);
    }

    static RegisterRequest copyRegister(RegisterRequest source) {
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
                ? new HashMap<>()
                : new HashMap<>(source.getMetadata()));
        return copy;
    }
}
