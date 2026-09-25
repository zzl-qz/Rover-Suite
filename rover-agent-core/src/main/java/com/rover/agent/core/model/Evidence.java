package com.rover.agent.core.model;

/**
 * 一条只读证据：来自某个只读数据源的观测事实。
 *
 * @param source           数据来源，与取得该事实的只读接口对应，例如 {@code /api/routes}
 * @param observedAtMillis 取证时刻
 * @param detail           事实内容
 */
public record Evidence(String source, long observedAtMillis, String detail) { }