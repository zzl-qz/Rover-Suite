package com.rover.agent.core.snapshot;

import java.util.List;

/**
 * 某个请求路径的 Gateway 抽样追踪快照。
 *
 * {@code enabled=false} 是有效事实（追踪被关闭），不等同于读取失败。
 *
 * @param enabled          追踪功能是否开启
 * @param sampleRate       当前采样率
 * @param rows             与目标路径精确匹配的追踪记录
 * @param observedAtMillis 取证时刻
 */
public record TraceSnapshot(boolean enabled, double sampleRate, List<TraceRow> rows, long observedAtMillis) { }