package com.rover.common.exception;

/**
 * Author: Daylight
 * Created: 2026-08-08 10:34:00
 * Description: 表示注册中心处理过程中的业务异常
 *
 * 这个类是什么：注册中心模块专用的业务异常标记类型，继承自 RoverException。
 * 核心职责：区分「注册中心自身处理失败」(如实例已满、服务不存在等)与其它异常，
 * 便于服务端统一捕获并按码返回给客户端。
 * 被谁用：rover-registry 的注册/注销/心跳/查询处理链路抛错使用。
 */
public class RegistryException extends RoverException {
}
