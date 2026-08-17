package com.rover.nameserver.core.registration;

/**
 * Author: Daylight
 * Created: 2026-08-17 00:00:00
 * Description: 注册实例的会话所有者；TCP 以连接 ID 标识，HTTP 以客户端 sessionId 标识
 */
public record RegistrationOwner(Type type, String id) {

    public RegistrationOwner {
        if (type == null) {
            throw new IllegalArgumentException("注册所有者类型不能为空");
        }
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("注册所有者 ID 不能为空");
        }
    }

    public static RegistrationOwner tcp(String channelId) {
        return new RegistrationOwner(Type.TCP, channelId);
    }

    public static RegistrationOwner http(String sessionId) {
        return new RegistrationOwner(Type.HTTP, sessionId);
    }

    public enum Type {
        TCP,
        HTTP
    }
}
