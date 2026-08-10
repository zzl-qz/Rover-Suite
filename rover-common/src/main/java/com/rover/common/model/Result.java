package com.rover.common.model;

import lombok.Data;

/**
 * Author: Daylight
 * Created: 2026-08-08 10:34:00
 * Description: 封装通用接口返回码、消息和数据
 *
 * 这个类是什么：最通用的接口返回包装模型，泛型 T 表示业务数据。
 * 核心职责：统一携带状态码、提示消息与业务数据，供 REST/管理端接口返回。
 * 被谁用：管理端(rover-admin)、网关 HTTP 出口等需要统一返回结构的地方。
 */
@Data
public class Result<T> {

    /** 状态码，见 StatusConstants */
    private int code;
    /** 结果描述文案 */
    private String message;
    /** 业务数据 */
    private T data;
}
