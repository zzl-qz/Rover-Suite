package com.rover.common.protocol;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ProtocolFlagsTest {

    @Test
    void rejectsUndefinedAckCodeEvenWhenBitsAreSupported() {
        assertThrows(IllegalArgumentException.class, () -> ProtocolFlags.validateSupported((short) 0b11));
    }
}
