package com.rover.common.model;

import lombok.Data;

/**
 * Author: Daylight
 * Created: 2026-08-08 10:34:00
 * Description: 封装 Rover 网关响应的请求标识、状态和响应体
 *
 * 这个类是什么：Rover 网关面向客户端(HTTP 调用方)的响应模型。
 * 核心职责：把后端一次调用的请求 ID、响应状态与业务体捆绑返回，
 * 便于调用方按请求 ID 关联日志与排查问题。
 * 被谁用：rover-gateway 的响应装配代码，暴露给外部调用方。
 */
@Data
public class RoverResponse {

    /** 原始请求 ID，用于关联一次调用 */
    private String requestId;
    /** 响应状态，见 StatusConstants */
    private byte status;
    /** 序列化后的响应体 */
    private byte[] body;
}
