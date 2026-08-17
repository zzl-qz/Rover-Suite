package com.rover.nameserver.client.connection;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.channel.embedded.EmbeddedChannel;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class ClientChannelStateTest {

    @Test
    void inactiveBeforePublicationDoesNotBlockTheNextConnection() {
        ClientChannelState state = new ClientChannelState();
        EmbeddedChannel closedBeforePublication = new EmbeddedChannel();
        EmbeddedChannel nextConnection = new EmbeddedChannel();
        try {
            state.startAcceptingConnections();

            closedBeforePublication.close().syncUninterruptibly();
            assertFalse(state.deactivate(closedBeforePublication, null),
                    "channelInactive 早于 connect 发布时，它还不是当前连接");
            assertFalse(state.activate(closedBeforePublication),
                    "connect 返回后不能把已经 inactive 的 channel 写回");
            assertNull(state.current());

            assertTrue(state.activate(nextConnection),
                    "失活候选连接必须保持可重连状态，允许下一条活连接接管");
            assertSame(nextConnection, state.current());
        } finally {
            closedBeforePublication.finishAndReleaseAll();
            nextConnection.finishAndReleaseAll();
        }
    }

    @Test
    void delayedInactiveFromOldChannelCannotClearNewChannelOrItsRequests() {
        ClientChannelState state = new ClientChannelState();
        EmbeddedChannel oldConnection = new EmbeddedChannel();
        EmbeddedChannel newConnection = new EmbeddedChannel();
        AtomicBoolean cleanupCalled = new AtomicBoolean(false);
        try {
            state.startAcceptingConnections();
            assertTrue(state.activate(oldConnection));

            oldConnection.close().syncUninterruptibly();
            assertTrue(state.deactivate(oldConnection, null));
            assertTrue(state.activate(newConnection));

            assertFalse(state.deactivate(oldConnection, () -> cleanupCalled.set(true)));
            assertFalse(cleanupCalled.get(), "旧 channel 不得失败新 channel 的在途请求");
            assertSame(newConnection, state.current());
            assertTrue(state.isActive());
        } finally {
            oldConnection.finishAndReleaseAll();
            newConnection.finishAndReleaseAll();
        }
    }

    @Test
    void shutdownRejectsLateConnectionSuccess() {
        ClientChannelState state = new ClientChannelState();
        EmbeddedChannel lateConnection = new EmbeddedChannel();
        try {
            state.startAcceptingConnections();
            assertNull(state.stopAcceptingConnections());

            assertFalse(state.activate(lateConnection));
            assertNull(state.current());
        } finally {
            lateConnection.finishAndReleaseAll();
        }
    }
}
