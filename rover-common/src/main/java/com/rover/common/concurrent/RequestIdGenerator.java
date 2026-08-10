package com.rover.common.concurrent;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Author: Daylight
 * Created: 2026-08-08 17:55:00
 * Description: 请求 ID 生成器
 *
 * 这个类是什么：基于 AtomicLong 的全局递增请求 ID 生成器。
 * 核心职责：为每条请求分配单调递增、线程安全的 requestId，
 * 保证请求/响应能按 ID 精确配对。
 * 被谁用：客户端(RoverClient)、注册中心客户端等在发送消息前取号。
 */
public class RequestIdGenerator {

    /** 序号起点 1，getAndIncrement 保证线程安全且永不重复(溢出周期内) */
    private final AtomicLong sequence = new AtomicLong(1);

    /** @return 下一个请求 ID，单调递增 */
    public long next() {
        return sequence.getAndIncrement();
    }
}
