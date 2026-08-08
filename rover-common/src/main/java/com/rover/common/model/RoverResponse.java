/**
 * 作者：Daylight
 * 创建时间：2026-08-08 10:34:00
 * 描述：封装 Rover 网关响应的请求标识、状态和响应体
 */
package com.rover.common.model;

import lombok.Data;

@Data
public class RoverResponse {

    private String requestId;
    private byte status;
    private byte[] body;
}
