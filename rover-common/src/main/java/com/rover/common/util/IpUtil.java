package com.rover.common.util;

/**
 * Author: Daylight
 * Created: 2026-08-08 10:34:00
 * Description: 提供本机 IP 获取和端口可用性检查工具
 *
 * 这个类是什么：IP/端口相关的静态工具类。
 * 核心职责：获取本机对外可用的 IP(供实例注册上报地址)与检测端口是否可占用
 * (供服务器/代理端口分配校验)。
 * 被谁用：rover-registry/rover-gateway 启动时确定监听地址与注册地址。
 */
public final class IpUtil {

    /** 工具类不允许实例化 */
    private IpUtil() {
    }

    /**
     * 获取本机 IP 地址(实现待补全)。
     *
     * @return 本机 IP 字符串
     * @throws UnsupportedOperationException 尚未实现
     */
    public static String getLocalIp() {
        throw new UnsupportedOperationException("TODO");
    }

    /**
     * 检测指定端口当前是否可被占用(实现待补全)。
     *
     * @param port 待检测端口
     * @return true 表示端口空闲可用
     * @throws UnsupportedOperationException 尚未实现
     */
    public static boolean isPortAvailable(int port) {
        throw new UnsupportedOperationException("TODO");
    }
}
