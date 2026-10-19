package com.rover.agent.core.snapshot;

import com.rover.agent.core.model.EvidenceQuality;
import com.rover.agent.core.model.TimeRange;
import java.util.List;
import java.util.Map;

/** Gateway 原生最终响应窗口；请求计数不包含重试增量，facts 含可合并直方图与资源维度。 */
public record RouteWindowSnapshot(String routeId, TimeRange requestedRange, TimeRange coverageRange,
                                   long observedAtMillis, EvidenceQuality qualityStatus, long finalRequests,
                                   long final5xx, String samplingMode, Map<String, Object> facts,
                                   List<String> limitations) { }
