package com.rover.common.constants;

/**
 * Author: Daylight
 * Created: 2026-08-08 10:34:00
 * Description: 维护 Rover 框架名称、版本和默认超时等基础常量
 *
 * 这个类是什么：Rover 框架级别的全局基础常量。
 * 核心职责：统一框架名、版本号、默认超时等跨模块共用的基础数值，
 * 供客户端连通性检测、日志埋点、默认参数等场景引用。
 * 被谁用：Rover 各模块中需要引用框架元信息的代码。
 */
public final class RoverConstants {

    /** 工具类不允许实例化 */
    private RoverConstants() {
    }

    /** 框架名称 */
    public static final String FRAMEWORK_NAME = "rover";
    /** 当前框架版本号 */
    public static final String FRAMEWORK_VERSION = "1.0.0-SNAPSHOT";
    /** 全局默认请求超时(毫秒)，未显式指定时使用 */
    public static final long DEFAULT_TIMEOUT_MILLIS = 3000L;
}
