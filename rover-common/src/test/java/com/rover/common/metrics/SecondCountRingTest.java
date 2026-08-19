package com.rover.common.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SecondCountRingTest {

    @Test
    void increment_countsCurrentAndPreviousSecond() {
        SecondCountRing ring = new SecondCountRing(8);
        ring.increment(100);
        ring.increment(100);
        ring.increment(101);
        assertEquals(2, ring.countAt(100));
        assertEquals(1, ring.countAt(101));
        assertEquals(0, ring.countAt(99));
        assertEquals(3, ring.sum(101, 3));
    }
}
