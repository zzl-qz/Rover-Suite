package com.rover.common.concurrent;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Author: Daylight
 * Created: 2026-08-03 10:55:00
 * Description: 基于 AtomicLong 的线程安全请求 ID 生成器
 */
public class RequestIdGenerator {

    /** 序号起点 1，getAndIncrement 保证线程安全（溢出周期内不重复） */
    private final AtomicLong sequence = new AtomicLong(1);

    /** 下一个请求 ID，单调递增 */
    public long next() {
        return sequence.getAndIncrement();
    }
}
