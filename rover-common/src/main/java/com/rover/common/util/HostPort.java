package com.rover.common.util;

/**
 * Author: Daylight
 * Created: 2026-08-02 11:30:00
 * Description: host:port 解析工具，供 Gateway discovery 地址、Starter 连接 Nameserver 等复用
 */
public record HostPort(String host, int port) {

    /**
     * 必填解析：空则抛错。
     *
     * @param raw       原始字符串，如 127.0.0.1:8888
     * @param fieldName 配置项名，用于报错文案
     */
    public static HostPort require(String raw, String fieldName) {
        HostPort address = parseOrNull(raw, fieldName);
        if (address == null) {
            throw new IllegalArgumentException(fieldName + " 不能为空");
        }
        return address;
    }

    /**
     * 可选解析：空返回 null；有值则校验格式。
     *
     * @param raw       原始字符串
     * @param fieldName 配置项名，用于报错文案
     * @return 解析结果；raw 为空时 null
     */
    public static HostPort parseOrNull(String raw, String fieldName) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        int idx = value.lastIndexOf(':');
        if (idx <= 0 || idx == value.length() - 1) {
            throw new IllegalArgumentException(fieldName + " 格式应为 host:port，当前=" + raw);
        }
        String host = value.substring(0, idx).trim();
        int port;
        try {
            port = Integer.parseInt(value.substring(idx + 1).trim());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(fieldName + " 端口不是数字: " + raw, ex);
        }
        if (host.isBlank() || port <= 0 || port > 65535) {
            throw new IllegalArgumentException(fieldName + " 非法: " + raw);
        }
        return new HostPort(host, port);
    }
}
