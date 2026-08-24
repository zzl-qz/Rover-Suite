package com.rover.common.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Author: Daylight
 * Description: token 鉴权辅助：空白判断 + 常量时间比较，避免 String.equals 时序侧信道
 */
public final class TokenAuth {

    private TokenAuth() {
    }

    /** null 或空白视为未配置。 */
    public static boolean isBlank(String token) {
        return token == null || token.isBlank();
    }

    /**
     * 常量时间比较。调用方先处理「未配置则放行」；任一侧 null 视为不匹配。
     */
    public static boolean matches(String expected, String provided) {
        if (expected == null || provided == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }
}
