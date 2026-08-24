package com.rover.common.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** StrictSecurity 读取系统属性（测试里不碰环境变量，避免污染进程）。 */
class StrictSecurityTest {

    @AfterEach
    void clearProperty() {
        System.clearProperty(StrictSecurity.PROPERTY_NAME);
    }

    @Test
    void propertyTrueEnablesStrictMode() {
        System.setProperty(StrictSecurity.PROPERTY_NAME, "true");
        assertTrue(StrictSecurity.enabled());
    }

    @Test
    void propertyFalseDisablesStrictMode() {
        System.setProperty(StrictSecurity.PROPERTY_NAME, "false");
        assertFalse(StrictSecurity.enabled());
    }
}
