package com.rover.common.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TokenAuthTest {

    @Test
    void blankTreatsNullAndWhitespaceAsUnset() {
        assertTrue(TokenAuth.isBlank(null));
        assertTrue(TokenAuth.isBlank(""));
        assertTrue(TokenAuth.isBlank("  "));
        assertFalse(TokenAuth.isBlank("secret"));
    }

    @Test
    void matchesRequiresBothSidesAndComparesBytes() {
        assertFalse(TokenAuth.matches("a", null));
        assertFalse(TokenAuth.matches(null, "a"));
        assertFalse(TokenAuth.matches("secret", "wrong"));
        assertTrue(TokenAuth.matches("secret", "secret"));
    }
}
