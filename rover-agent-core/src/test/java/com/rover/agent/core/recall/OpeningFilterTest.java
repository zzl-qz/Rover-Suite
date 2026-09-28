package com.rover.agent.core.recall;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OpeningFilterTest {

    @Test
    void greetingIsAside() {
        assertTrue(OpeningFilter.aside("你好啊"));
        assertTrue(OpeningFilter.aside("你能做什么"));
    }

    @Test
    void aRealQuestionIsKept() {
        assertFalse(OpeningFilter.aside("你好，为什么 /api 调用失败？"));
        assertFalse(OpeningFilter.aside("帮我看看 demo-service"));
    }
}
