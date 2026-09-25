package com.rover.agent.core.snapshot;

/**
 * 一条 Gateway 请求追踪记录。
 *
 * @param traceId    链路 ID
 * @param path       请求路径
 * @param statusCode 响应状态码
 * @param startMillis 请求开始时刻
 */
public record TraceRow(String traceId, String path, int statusCode, long startMillis) { }