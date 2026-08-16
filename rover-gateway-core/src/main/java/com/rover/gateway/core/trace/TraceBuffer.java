package com.rover.gateway.core.trace;

import java.util.ArrayList;
import java.util.List;

/**
 * Author: Daylight
 * Description: 请求时间线环形缓冲：固定容量，写满覆盖最旧数据，绝不阻塞请求线程。
 * 追加与快照都加锁，但量级极小（只有慢请求/采样命中才会写入），对主链路无压力。
 */
public class TraceBuffer {

    /** 默认保留最近条数。 */
    public static final int DEFAULT_CAPACITY = 200;

    private final RequestTrace[] buffer;
    private final int capacity;
    private int next;
    private int size;

    public TraceBuffer() {
        this(DEFAULT_CAPACITY);
    }

    public TraceBuffer(int capacity) {
        this.capacity = Math.max(1, capacity);
        this.buffer = new RequestTrace[this.capacity];
    }

    /** 追加一条 trace，写满时覆盖最旧。 */
    public synchronized void append(RequestTrace trace) {
        buffer[next] = trace;
        next = (next + 1) % capacity;
        if (size < capacity) {
            size++;
        }
    }

    /** 按时间倒序（最新在前）返回快照。 */
    public synchronized List<RequestTrace> snapshot() {
        List<RequestTrace> result = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            int index = (next - 1 - i + capacity * 2) % capacity;
            RequestTrace trace = buffer[index];
            if (trace != null) {
                result.add(trace);
            }
        }
        return result;
    }

    public synchronized int size() {
        return size;
    }

    public int capacity() {
        return capacity;
    }
}
