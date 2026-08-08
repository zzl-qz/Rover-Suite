package com.rover.common.concurrent;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Author: Daylight
 * Created: 2026-08-08 17:55:00
 * Description: 请求 ID 生成器
 */
public class RequestIdGenerator {

    private final AtomicLong sequence = new AtomicLong(1);

    public long next() {
        return sequence.getAndIncrement();
    }
}
