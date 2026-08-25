package com.rover.gateway.core.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.LastHttpContent;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse.ResponseInfo;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;

class HttpProxyClientTest {

    private static EmbeddedChannel newChannel() {
        return new EmbeddedChannel(new ChannelInboundHandlerAdapter());
    }

    @Test
    void streamingSubscriberFailsBeforeBufferingPastLimit() {
        EmbeddedChannel channel = newChannel();
        HttpProxyClient.StreamingResponseSubscriber subscriber =
                new HttpProxyClient.StreamingResponseSubscriber(
                        channel.pipeline().firstContext(),
                        new FixedResponseInfo(200),
                        4);

        subscriber.onSubscribe(new NoopSubscription());
        // 等 EventLoop 写出响应头
        channel.runPendingTasks();
        assertInstanceOf(HttpResponse.class, channel.readOutbound());

        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[]{1, 2, 3})));
        channel.runPendingTasks();

        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[]{4, 5})));
        channel.runPendingTasks();

        assertThrows(CompletionException.class,
                () -> subscriber.getBody().toCompletableFuture().join());
    }

    @Test
    void streamingSubscriberWritesHeadersThenLastContent() {
        EmbeddedChannel channel = newChannel();
        HttpProxyClient.StreamingResponseSubscriber subscriber =
                new HttpProxyClient.StreamingResponseSubscriber(
                        channel.pipeline().firstContext(),
                        new FixedResponseInfo(204),
                        1024);

        subscriber.onSubscribe(new RequestingSubscription());
        channel.runPendingTasks();
        assertInstanceOf(HttpResponse.class, channel.readOutbound());

        subscriber.onComplete();
        channel.runPendingTasks();
        assertInstanceOf(LastHttpContent.class, channel.readOutbound());
        assertTrue(subscriber.getBody().toCompletableFuture().isDone());
    }

    @Test
    void redactsQueryAndUserInfo() {
        assertEquals(
                "http://example.com:8080/path",
                HttpProxyClient.redactTargetUrl("http://user:pass@example.com:8080/path?token=secret"));
    }

    private static final class FixedResponseInfo implements ResponseInfo {
        private final int status;

        private FixedResponseInfo(int status) {
            this.status = status;
        }

        @Override
        public int statusCode() {
            return status;
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(Map.of(), (a, b) -> true);
        }

        @Override
        public java.net.http.HttpClient.Version version() {
            return java.net.http.HttpClient.Version.HTTP_1_1;
        }
    }

    private static final class NoopSubscription implements Flow.Subscription {
        @Override
        public void request(long n) {
        }

        @Override
        public void cancel() {
        }
    }

    /** onSubscribe 里会 request；这里再 request 也无妨。 */
    private static final class RequestingSubscription implements Flow.Subscription {
        @Override
        public void request(long n) {
        }

        @Override
        public void cancel() {
        }
    }
}
