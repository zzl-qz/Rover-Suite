package com.rover.common.model;

import lombok.Data;

/**
 * Author: Daylight
 * Created: 2026-08-08 10:34:00
 * Description: 封装通用接口返回码、消息和数据
 */
@Data
public class Result<T> {

    private int code;
    private String message;
    private T data;
}
