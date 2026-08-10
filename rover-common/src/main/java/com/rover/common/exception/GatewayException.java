package com.rover.common.exception;

/**
 * Author: Daylight
 * Created: 2026-08-08 10:34:00
 * Description: 表示网关处理过程中的业务异常
 *
 * 这个类是什么：网关模块专用的业务异常标记类型，继承自 RoverException。
 * 核心职责：区分「网关自身处理失败」与其他模块异常，便于网关侧统一捕获、
 * 按异常类型决定返回码(如 500/400)与错误文案。
 * 被谁用：rover-gateway 的过滤器、路由转发与响应构造代码抛错使用。
 */
public class GatewayException extends RoverException {
}
