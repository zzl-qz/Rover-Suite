package com.rover.common.metrics;

import java.util.concurrent.atomic.LongAdder;

/**
 * Author: Daylight
 * Description: 按秒计数的固定环形窗口。热路径只加 LongAdder，切秒时才清基线。
 * 给 Nameserver 心跳/查询/推送这类「只要次数、不要耗时」的瞬时值用。
 */
public final class SecondCountRing {

    private final Slot[] slots;

    public SecondCountRing(int seconds) {
        if (seconds <= 0) {
            throw new IllegalArgumentException("seconds 必须大于 0");
        }
        this.slots = new Slot[seconds];
        for (int i = 0; i < seconds; i++) {
            slots[i] = new Slot();
        }
    }

    /** 当前秒 +1。 */
    public void increment() {
        increment(System.currentTimeMillis() / 1000);
    }

    /** 指定秒 +1。 */
    public void increment(long epochSecond) {
        add(epochSecond, 1);
    }

    /** 指定秒加上 delta。 */
    public void add(long epochSecond, long delta) {
        if (delta <= 0) {
            return;
        }
        Slot slot = slots[(int) Math.floorMod(epochSecond, slots.length)];
        if (slot.stamp != epochSecond) {
            synchronized (slot) {
                if (slot.stamp != epochSecond) {
                    slot.base = slot.count.sum();
                    slot.stamp = epochSecond;
                }
            }
        }
        slot.count.add(delta);
    }

    /** 某一秒的次数，过期秒返回 0。 */
    public long countAt(long epochSecond) {
        Slot slot = slots[(int) Math.floorMod(epochSecond, slots.length)];
        return slot.stamp == epochSecond ? Math.max(0, slot.count.sum() - slot.base) : 0;
    }

    /** 窗口内求和。 */
    public long sum(long nowSecond, int windowSeconds) {
        int window = Math.min(Math.max(1, windowSeconds), slots.length);
        long total = 0;
        for (long second = nowSecond - window + 1; second <= nowSecond; second++) {
            total += countAt(second);
        }
        return total;
    }

    private static final class Slot {
        volatile long stamp;
        volatile long base;
        final LongAdder count = new LongAdder();
    }
}
