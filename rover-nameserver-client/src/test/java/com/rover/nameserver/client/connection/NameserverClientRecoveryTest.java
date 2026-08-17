package com.rover.nameserver.client.connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rover.common.constants.ProtocolConstants;
import com.rover.common.constants.StatusConstants;
import com.rover.common.protocol.CommonResponseBody;
import com.rover.common.protocol.RegisterRequest;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NameserverClientRecoveryTest {

    private StubNameserverClient client;
    private RegisterRequest registerRequest;

    @BeforeEach
    void setUp() {
        client = new StubNameserverClient();
        registerRequest = new RegisterRequest();
        registerRequest.setServiceName("order-service");
        registerRequest.setInstanceId("127.0.0.1:8080");
        registerRequest.setHost("127.0.0.1");
        registerRequest.setPort(8080);

        client.register(registerRequest);
        client.clearCalls();
    }

    @Test
    void heartbeatNotFoundTriggersImmediateReregistration() {
        client.respond(ProtocolConstants.HEARTBEAT_REQUEST,
                CommonResponseBody.fail(StatusConstants.SERVICE_NOT_FOUND, "实例不存在"));
        client.respond(ProtocolConstants.REGISTER_REQUEST, CommonResponseBody.success());

        client.heartbeatRegistered();

        assertEquals(
                List.of(ProtocolConstants.HEARTBEAT_REQUEST, ProtocolConstants.REGISTER_REQUEST),
                client.calls());
    }

    @Test
    void failedReregistrationIsRetriedOnNextFixedHeartbeatTick() {
        client.respond(ProtocolConstants.HEARTBEAT_REQUEST,
                CommonResponseBody.fail(StatusConstants.SERVICE_NOT_FOUND, "实例不存在"));
        client.respond(ProtocolConstants.REGISTER_REQUEST,
                CommonResponseBody.fail(StatusConstants.SERVER_ERROR, "暂时不可用"));
        client.respond(ProtocolConstants.REGISTER_REQUEST, CommonResponseBody.success());

        client.heartbeatRegistered();
        assertEquals(
                List.of(ProtocolConstants.HEARTBEAT_REQUEST, ProtocolConstants.REGISTER_REQUEST),
                client.calls());

        client.clearCalls();
        client.heartbeatRegistered();
        assertEquals(List.of(ProtocolConstants.REGISTER_REQUEST), client.calls());

        client.clearCalls();
        client.heartbeatRegistered();
        assertEquals(List.of(ProtocolConstants.HEARTBEAT_REQUEST), client.calls());
    }

    @Test
    void recoverStateTreatsErrorResponseAsPendingAndRetriesLater() {
        client.respond(ProtocolConstants.REGISTER_REQUEST,
                CommonResponseBody.fail(StatusConstants.SERVER_ERROR, "暂时不可用"));
        client.respond(ProtocolConstants.REGISTER_REQUEST, CommonResponseBody.success());

        client.recoverState();
        assertEquals(List.of(ProtocolConstants.REGISTER_REQUEST), client.calls());

        client.clearCalls();
        client.recoverPendingState();
        assertEquals(List.of(ProtocolConstants.REGISTER_REQUEST), client.calls());
    }

    @Test
    void staleTcpOwnerStopsHeartbeatInsteadOfFightingForOwnership() {
        client.respond(ProtocolConstants.HEARTBEAT_REQUEST,
                CommonResponseBody.fail(StatusConstants.CONFLICT, "实例已由新会话接管"));

        client.heartbeatRegistered();
        assertEquals(List.of(ProtocolConstants.HEARTBEAT_REQUEST), client.calls());

        client.clearCalls();
        client.heartbeatRegistered();
        assertEquals(List.of(), client.calls());
    }

    @Test
    void explicitUnregisterCannotBeOvertakenByInFlightRecovery() throws Exception {
        client.blockNextRegistration();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread recovery = new Thread(client::recoverState, "test-registration-recovery");
        recovery.setUncaughtExceptionHandler((thread, error) -> failure.set(error));
        recovery.start();
        assertTrue(client.awaitBlockedRegistration(), "恢复注册应已进入请求");

        CountDownLatch unregisterStarted = new CountDownLatch(1);
        Thread unregister = new Thread(() -> {
            unregisterStarted.countDown();
            client.unregister(registerRequest.getServiceName(), registerRequest.getInstanceId());
        }, "test-explicit-unregister");
        unregister.setUncaughtExceptionHandler((thread, error) -> failure.set(error));
        unregister.start();
        assertTrue(unregisterStarted.await(1, TimeUnit.SECONDS));

        Thread.sleep(30);
        assertEquals(List.of(ProtocolConstants.REGISTER_REQUEST), client.calls(),
                "同实例的显式注销必须等待恢复注册完成，不能与它并发交叉");

        client.releaseBlockedRegistration();
        recovery.join(1_000);
        unregister.join(1_000);
        assertFalse(recovery.isAlive());
        assertFalse(unregister.isAlive());
        assertNull(failure.get());
        assertEquals(
                List.of(ProtocolConstants.REGISTER_REQUEST, ProtocolConstants.UNREGISTER_REQUEST),
                client.calls());

        client.clearCalls();
        client.heartbeatRegistered();
        assertEquals(List.of(), client.calls(), "注销成功后不能再被恢复任务续租");
    }

    private static final class StubNameserverClient extends NameserverClient {

        private final Map<Byte, Deque<CommonResponseBody>> responses = new HashMap<>();
        private final List<Byte> calls = new CopyOnWriteArrayList<>();
        private final AtomicBoolean blockNextRegistration = new AtomicBoolean(false);
        private final CountDownLatch registrationBlocked = new CountDownLatch(1);
        private final CountDownLatch releaseRegistration = new CountDownLatch(1);

        private StubNameserverClient() {
            super(NameserverClientOptions.builder()
                    .host("127.0.0.1")
                    .port(9876)
                    .build());
        }

        @Override
        public boolean isActive() {
            return true;
        }

        @Override
        public CommonResponseBody requestSync(byte type, Object body) {
            calls.add(type);
            if (type == ProtocolConstants.REGISTER_REQUEST
                    && blockNextRegistration.compareAndSet(true, false)) {
                registrationBlocked.countDown();
                try {
                    if (!releaseRegistration.await(1, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("等待释放注册请求超时");
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ex);
                }
            }
            Deque<CommonResponseBody> queued = responses.get(type);
            return queued == null || queued.isEmpty()
                    ? CommonResponseBody.success()
                    : queued.removeFirst();
        }

        private void respond(byte type, CommonResponseBody response) {
            responses.computeIfAbsent(type, ignored -> new ArrayDeque<>()).addLast(response);
        }

        private List<Byte> calls() {
            return List.copyOf(calls);
        }

        private void clearCalls() {
            calls.clear();
        }

        private void blockNextRegistration() {
            blockNextRegistration.set(true);
        }

        private boolean awaitBlockedRegistration() throws InterruptedException {
            return registrationBlocked.await(1, TimeUnit.SECONDS);
        }

        private void releaseBlockedRegistration() {
            releaseRegistration.countDown();
        }
    }
}
