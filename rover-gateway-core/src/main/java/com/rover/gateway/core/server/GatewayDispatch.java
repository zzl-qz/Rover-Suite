package com.rover.gateway.core.server;

import com.rover.gateway.core.config.GatewaySystemProperties;

/**
 * 业务 Handler 是否挂在 EventLoop。
 * 默认 false：I/O 和业务分开，走 biz 池。确认无阻塞才开 true。
 */
public final class GatewayDispatch {

    private GatewayDispatch() {
    }

    public static boolean onEventLoop() {
        return Boolean.parseBoolean(
                System.getProperty(GatewaySystemProperties.DISPATCH_ON_EVENT_LOOP, "false"));
    }
}
