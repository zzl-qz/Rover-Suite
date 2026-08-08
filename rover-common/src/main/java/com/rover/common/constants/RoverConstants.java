/**
 * 作者：Daylight
 * 创建时间：2026-08-08 10:34:00
 * 描述：维护 Rover 框架名称、版本和默认超时等基础常量
 */
package com.rover.common.constants;

public final class RoverConstants {

    private RoverConstants() {
    }

    public static final String FRAMEWORK_NAME = "rover";
    public static final String FRAMEWORK_VERSION = "1.0.0-SNAPSHOT";
    public static final long DEFAULT_TIMEOUT_MILLIS = 3000L;
}
