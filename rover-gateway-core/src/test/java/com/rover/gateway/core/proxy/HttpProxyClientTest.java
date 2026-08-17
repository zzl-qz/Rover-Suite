package com.rover.gateway.core.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;

class HttpProxyClientTest {

    @Test
    void responseSubscriberFailsBeforeBufferingPastLimit() {
        HttpProxyClient.LimitedByteArraySubscriber subscriber =
                new HttpProxyClient.LimitedByteArraySubscriber(4);
        subscriber.onSubscribe(new NoopSubscription());
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[]{1, 2, 3})));
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[]{4, 5})));

        assertThrows(CompletionException.class,
                () -> subscriber.getBody().toCompletableFuture().join());
    }

    @Test
    void redactsQueryAndUserInfo() {
        assertEquals(
                "http://example.com:8080/path",
                HttpProxyClient.redactTargetUrl("http://user:pass@example.com:8080/path?token=secret"));
    }

    private static final class NoopSubscription implements Flow.Subscription {
        @Override
        public void request(long n) {
        }

        @Override
        public void cancel() {
        }
    }
}
