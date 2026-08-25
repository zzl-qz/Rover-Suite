package com.rover.gateway.core.proxy;

import java.util.concurrent.atomic.AtomicLong;

/** 热路径请求号：不要每次 UUID。nano + 序号够日志对得上。 */
final class RequestIds {

    private static final AtomicLong SEQ = new AtomicLong();

    private RequestIds() {
    }

    static String next() {
        return Long.toHexString(System.nanoTime()) + Long.toHexString(SEQ.incrementAndGet());
    }
}
