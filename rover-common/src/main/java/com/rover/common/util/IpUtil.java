package com.rover.common.util;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import lombok.extern.slf4j.Slf4j;

/**
 * Author: Daylight
 * Created: 2026-08-08 10:34:00
 * Description: 本机对外 IP 探测工具：返回可用 IPv4 地址，供实例注册时自动上报 host
 */
@Slf4j
public final class IpUtil {

    /** 工具类不允许实例化 */
    private IpUtil() {
    }

    /**
     * 获取本机对外可用的 IPv4 地址。
     *
     * 优先取第一个「已启用、非回环、非虚拟」网卡上的 IPv4 地址；
     * 取不到时回退到 InetAddress.getLocalHost()，仍失败则回退 127.0.0.1。
     *
     * @return 本机 IPv4 字符串，如 192.168.1.10
     */
    public static String getLocalIp() {
        try {
            Enumeration<NetworkInterface> networks = NetworkInterface.getNetworkInterfaces();
            while (networks.hasMoreElements()) {
                NetworkInterface network = networks.nextElement();
                if (!network.isUp() || network.isLoopback() || network.isVirtual()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = network.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (!address.isLoopbackAddress() && address.getHostAddress().indexOf(':') < 0) {
                        return address.getHostAddress();
                    }
                }
            }
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception ex) {
            log.warn("自动探测本机 IP 失败，回退 127.0.0.1", ex);
            return "127.0.0.1";
        }
    }
}
