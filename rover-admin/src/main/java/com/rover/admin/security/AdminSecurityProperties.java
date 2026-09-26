package com.rover.admin.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 控制台登录鉴权配置。
 *
 * 沿用项目既有约定：**留空 = 不鉴权，仅本机调试**（与 {@code rover.admin.admin-token} 的注释一致）。
 * 未配置口令时不启用登录，但启动打 WARN、页面顶栏显示提示条，让不安全状态绝不静默。
 *
 * 口令首选 {@code password-hash}（BCrypt）；配置明文 {@code password} 时启动期哈希进内存，
 * 无论哪种方式都不记录明文口令。
 */
@ConfigurationProperties(prefix = AdminSecurityProperties.PREFIX)
public class AdminSecurityProperties {

    public static final String PREFIX = "rover.admin.auth";

    private String username = "admin";
    /** BCrypt 哈希；与明文口令同时存在时以本项为准。 */
    private String passwordHash = "";
    /** 明文口令，仅用于本机快速启动，启动期即哈希进内存。 */
    private String password = "";
    /** 滑动窗口内允许的连续登录失败次数。 */
    private int maxLoginFailures = 5;
    /** 登录失败计数窗口，单位为秒。 */
    private int failureWindowSeconds = 600;

    /** 是否启用登录鉴权：只有确实提供了口令才算启用。 */
    public boolean isEnabled() {
        return hasText(passwordHash) || hasText(password);
    }

    /** 是否用的是明文口令（提示改用哈希）。 */
    public boolean isPlainTextPassword() {
        return !hasText(passwordHash) && hasText(password);
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public int getMaxLoginFailures() {
        return maxLoginFailures;
    }

    public void setMaxLoginFailures(int maxLoginFailures) {
        this.maxLoginFailures = maxLoginFailures;
    }

    public int getFailureWindowSeconds() {
        return failureWindowSeconds;
    }

    public void setFailureWindowSeconds(int failureWindowSeconds) {
        this.failureWindowSeconds = failureWindowSeconds;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}