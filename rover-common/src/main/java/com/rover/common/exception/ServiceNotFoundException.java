package com.rover.common.exception;

/**
 * Author: Daylight
 * Created: 2026-08-08 10:34:00
 * Description: 表示目标服务不存在的异常
 *
 * 这个类是什么：目标服务缺失专用异常，继承自 RoverException。
 * 核心职责：在网关转发、注册中心查询或服务发现时找不到目标服务的情况下抛出，
 * 上层据此映射为 404(SERVICE_NOT_FOUND) 返回给调用方。
 * 被谁用：网关路由转发、客户端服务发现等找不到服务实例的代码。
 */
public class ServiceNotFoundException extends RoverException {
}
