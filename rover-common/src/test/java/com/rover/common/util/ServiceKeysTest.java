package com.rover.common.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class ServiceKeysTest {

    @Test
    void compositeKeysDoNotCollideWhenValuesContainLegacySeparator() {
        assertNotEquals(
                ServiceKeys.serviceGroup("a#b", "c"),
                ServiceKeys.serviceGroup("a", "b#c"));
        assertEquals(
                ServiceKeys.serviceGroup("service", null),
                ServiceKeys.serviceGroup("service", ""));
    }
}
