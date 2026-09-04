package com.rover.gateway.core.server;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

/**
 * 请求还没收齐，客户端半截不送。到期直接关这根连接，让 abort 把上游也拆掉。
 * 跟 Keep-Alive 空闲回收不是一回事：那个只踢没占 BUSY 的闲管子。
 */
@Slf4j
final class InboundRequestStall {

    private static final AttributeKey<ScheduledFuture<?>> STALL =
            AttributeKey.valueOf("rover.inbound.requestStall");

    private InboundRequestStall() {
    }

    static void arm(Channel channel, int timeoutSeconds) {
        cancel(channel);
        if (timeoutSeconds <= 0 || channel == null || !channel.isActive()) {
            return;
        }
        ScheduledFuture<?> future = channel.eventLoop().schedule(() -> {
            if (channel.isActive()) {
                log.warn("Inbound request stalled, closing connection");
                channel.close();
            }
        }, timeoutSeconds, TimeUnit.SECONDS);
        channel.attr(STALL).set(future);
    }

    static void cancel(Channel channel) {
        if (channel == null) {
            return;
        }
        ScheduledFuture<?> future = channel.attr(STALL).getAndSet(null);
        if (future != null) {
            future.cancel(false);
        }
    }
}
