/**
 * 作者：Daylight
 * 创建时间：2026-08-08 10:34:00
 * 描述：封装通用接口返回码、消息和数据
 */
package com.rover.common.model;

import lombok.Data;

@Data
public class Result<T> {

    private int code;
    private String message;
    private T data;
}
