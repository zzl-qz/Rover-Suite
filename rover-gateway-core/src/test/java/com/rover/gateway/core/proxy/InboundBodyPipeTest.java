package com.rover.gateway.core.proxy;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.LastHttpContent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class InboundBodyPipeTest {

    @Test
    void queuesUntilAttachedThenDrains() {
        InboundBodyPipe pipe = new InboundBodyPipe(1024);
        List<String> seen = new ArrayList<>();
        pipe.offer(new DefaultHttpContent(Unpooled.copiedBuffer("ab", StandardCharsets.UTF_8)));

        pipe.attach(chunk -> {
            seen.add(chunk.content().toString(StandardCharsets.UTF_8));
            chunk.release();
        });
        assertEquals(List.of("ab"), seen);

        pipe.offer(new DefaultLastHttpContent(Unpooled.copiedBuffer("c", StandardCharsets.UTF_8)));
        assertEquals(List.of("ab", "c"), seen);
    }

    @Test
    void collectBytesAfterLast() throws Exception {
        InboundBodyPipe pipe = new InboundBodyPipe(1024);
        pipe.offer(new DefaultHttpContent(Unpooled.copiedBuffer("he", StandardCharsets.UTF_8)));
        pipe.offer(new DefaultLastHttpContent(Unpooled.copiedBuffer("y", StandardCharsets.UTF_8)));
        assertArrayEquals("hey".getBytes(StandardCharsets.UTF_8), pipe.collectBytes().get(1, TimeUnit.SECONDS));
    }

    @Test
    void overflowRejectsAndReleases() {
        InboundBodyPipe pipe = new InboundBodyPipe(2);
        HttpContent tooBig = new DefaultLastHttpContent(Unpooled.copiedBuffer("abcd", StandardCharsets.UTF_8));
        assertFalse(pipe.offer(tooBig));
        assertTrue(pipe.overflowed());
        assertEquals(0, tooBig.refCnt());
    }

    @Test
    void emptyLastIsFine() throws Exception {
        InboundBodyPipe pipe = InboundBodyPipe.empty(16);
        assertEquals(0, pipe.collectBytes().get(1, TimeUnit.SECONDS).length);
        pipe.offer(LastHttpContent.EMPTY_LAST_CONTENT);
    }

    @Test
    void canReplayUntilAttachedOrAborted() {
        InboundBodyPipe pipe = new InboundBodyPipe(1024);
        pipe.offer(new DefaultLastHttpContent(Unpooled.copiedBuffer("x", StandardCharsets.UTF_8)));
        assertTrue(pipe.canReplay());

        pipe.attach(HttpContent::release);
        assertFalse(pipe.canReplay());
    }

    @Test
    void abortStopsReplay() {
        InboundBodyPipe pipe = InboundBodyPipe.empty(16);
        assertTrue(pipe.canReplay());
        pipe.abort();
        assertFalse(pipe.canReplay());
    }

    @Test
    void collectBytesStillReplayableForJdkConnectFail() throws Exception {
        InboundBodyPipe pipe = new InboundBodyPipe(1024);
        pipe.offer(new DefaultLastHttpContent(Unpooled.copiedBuffer("ab", StandardCharsets.UTF_8)));
        assertArrayEquals("ab".getBytes(StandardCharsets.UTF_8), pipe.collectBytes().get(1, TimeUnit.SECONDS));
        assertTrue(pipe.canReplay(), "JDK 连不上时 byte[] 还在，可以换台再发");
        assertArrayEquals("ab".getBytes(StandardCharsets.UTF_8), pipe.collectBytes().get(1, TimeUnit.SECONDS));
    }
}
