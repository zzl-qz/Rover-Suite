package com.rover.agent.core.model;

import java.util.Map;
import java.util.UUID;

/**
 * 一条只读证据：来自某个只读数据源的观测事实。证据不是字符串，而是可追溯到来源的结构化记录。
 *
 * {@code source} 是给人看的来源名（如「Gateway 路由表」），{@code rawReference} 是取数地址
 * （如 {@code /api/routes}），两者都不能省：前者用于展示，后者用于复核与模型工具定位。
 * {@code metadata} 保留该来源的结构化补充；本轮没有可填内容，一律为空 Map。
 *
 * @param evidenceId       证据 ID
 * @param taskId           产出该证据的调查任务
 * @param type             证据种类
 * @param source           数据来源（人读）
 * @param title            证据标题
 * @param summary          事实内容
 * @param rawReference     取数地址
 * @param metadata         结构化补充；本轮为空
 * @param observedAtMillis 取证时刻
 */
public record Evidence(String evidenceId, String taskId, EvidenceType type, String source, String title,
                       String summary, String rawReference, Map<String, String> metadata, long observedAtMillis) {

    /** 采集一条证据；ID 在此生成，{@code metadata} 留空。 */
    public static Evidence of(String taskId, EvidenceType type, String source, String title, String summary,
                              String rawReference, long observedAtMillis) {
        return new Evidence(UUID.randomUUID().toString(), taskId, type, source, title, summary, rawReference,
                Map.of(), observedAtMillis);
    }
}