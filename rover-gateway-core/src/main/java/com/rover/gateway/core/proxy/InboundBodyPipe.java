package com.rover.gateway.core.proxy;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.LastHttpContent;
import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;

/**
 * 入站 body 管道：chunk 先到就排队，出站连上再往下冲。
 * 同一条连接的读和出站回调不在一个线程，必须同步。
 */
public final class InboundBodyPipe {

    public interface Sink {
        /** chunk 所有权转给 sink，用完必须 release。 */
        void accept(HttpContent chunk);
    }

    private final int maxBytes;
    private final ArrayDeque<HttpContent> pending = new ArrayDeque<>();
    private Sink sink;
    private int received;
    private boolean lastSeen;
    private boolean aborted;
    private boolean overflow;
    private boolean attached;
    private CompletableFuture<byte[]> bytesFuture;
    private Runnable abortedCallback;

    public InboundBodyPipe(int maxBytes) {
        this.maxBytes = maxBytes;
    }

    /** 单测 / JDK 对照：已经握着整包时，当成「最后一个 chunk」塞进去。 */
    public static InboundBodyPipe fromFull(FullHttpRequest request, int maxBytes) {
        InboundBodyPipe pipe = new InboundBodyPipe(maxBytes);
        ByteBuf content = request.content();
        HttpContent last = content.readableBytes() == 0
                ? LastHttpContent.EMPTY_LAST_CONTENT
                : new DefaultLastHttpContent(content.retainedDuplicate());
        pipe.offer(last);
        return pipe;
    }

    public static InboundBodyPipe empty(int maxBytes) {
        InboundBodyPipe pipe = new InboundBodyPipe(maxBytes);
        pipe.offer(LastHttpContent.EMPTY_LAST_CONTENT);
        return pipe;
    }

    /** 出站连上后挂上；已经排队的 chunk 马上冲下去。 */
    public synchronized void attach(Sink sink) {
        if (aborted) {
            return;
        }
        this.sink = sink;
        this.attached = true;
        drainToSink();
    }

    /** 还没挂到上游、也没 abort，就能换台再发。 */
    public synchronized boolean canReplay() {
        return !aborted && !attached;
    }

    /**
     * 收下入站 chunk（所有权转给管道）。
     * @return false 超限，调用方回 413；chunk 已被释放
     */
    public boolean offer(HttpContent content) {
        Runnable callback;
        boolean ok;
        synchronized (this) {
            if (aborted || lastSeen) {
                content.release();
                return !overflow;
            }
            int size = content.content().readableBytes();
            if (received + size > maxBytes) {
                content.release();
                overflow = true;
                callback = markAborted();
                ok = false;
            } else {
                received += size;
                pending.add(content);
                if (content instanceof LastHttpContent) {
                    lastSeen = true;
                    if (bytesFuture != null && sink == null) {
                        completeBytes();
                    }
                }
                drainToSink();
                callback = null;
                ok = true;
            }
        }
        fireAborted(callback);
        return ok;
    }

    public synchronized boolean overflowed() {
        return overflow;
    }

    public synchronized boolean isAborted() {
        return aborted;
    }

    /**
     * 入站拆了就通知出站。已经 abort 过的立刻跑，别等第二次。
     * 回调在锁外跑，避免出站 fail 再进 abort 把自己卡死。
     */
    public void whenAborted(Runnable callback) {
        boolean runNow;
        synchronized (this) {
            abortedCallback = callback;
            runNow = aborted;
        }
        if (runNow) {
            callback.run();
        }
    }

    /** JDK 出站要整段 byte[]；Netty 路径不要调这个。 */
    public synchronized CompletableFuture<byte[]> collectBytes() {
        if (bytesFuture == null) {
            bytesFuture = new CompletableFuture<>();
            if (aborted) {
                bytesFuture.completeExceptionally(new IllegalStateException("inbound body aborted"));
            } else if (lastSeen && sink == null) {
                completeBytes();
            }
        }
        return bytesFuture;
    }

    public void abort() {
        Runnable callback;
        synchronized (this) {
            callback = markAborted();
        }
        fireAborted(callback);
    }

    /** 刚标上 aborted 才把回调交出去；已经 abort 过的不再触发。 */
    private Runnable markAborted() {
        if (aborted) {
            releasePending();
            return null;
        }
        aborted = true;
        sink = null;
        releasePending();
        if (bytesFuture != null && !bytesFuture.isDone()) {
            bytesFuture.completeExceptionally(new IllegalStateException("inbound body aborted"));
        }
        Runnable callback = abortedCallback;
        abortedCallback = null;
        return callback;
    }

    private static void fireAborted(Runnable callback) {
        if (callback != null) {
            callback.run();
        }
    }

    private void drainToSink() {
        if (sink == null) {
            return;
        }
        HttpContent chunk;
        while ((chunk = pending.poll()) != null) {
            sink.accept(chunk);
        }
    }

    private void completeBytes() {
        int total = 0;
        for (HttpContent chunk : pending) {
            total += chunk.content().readableBytes();
        }
        byte[] out = total == 0 ? new byte[0] : new byte[total];
        int pos = 0;
        HttpContent chunk;
        while ((chunk = pending.poll()) != null) {
            ByteBuf buf = chunk.content();
            int n = buf.readableBytes();
            if (n > 0) {
                buf.getBytes(buf.readerIndex(), out, pos, n);
                pos += n;
            }
            chunk.release();
        }
        bytesFuture.complete(out);
    }

    private void releasePending() {
        HttpContent chunk;
        while ((chunk = pending.poll()) != null) {
            chunk.release();
        }
    }
}
