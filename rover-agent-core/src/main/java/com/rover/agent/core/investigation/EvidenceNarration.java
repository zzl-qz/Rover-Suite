package com.rover.agent.core.investigation;

import java.util.List;

/**
 * 证据事实、判断边界及统计口径；windowSeconds=0 表示时点快照。
 *
 * @param detail        写进证据列表的事实说明
 * @param limitations   由该事实带来的判断边界
 * @param sampleSize    这份证据的样本量（窗口内记录数 / 路由条数 / 配置条目数等）
 * @param windowSeconds 统计窗口秒数；0 表示非窗口口径
 */
public record EvidenceNarration(String detail, List<String> limitations, long sampleSize, int windowSeconds) {

    /** 非窗口口径（时点快照）：样本量按 1 条记录处理，由调用方按需覆盖。 */
    public EvidenceNarration(String detail, List<String> limitations) {
        this(detail, limitations, 1, 0);
    }
}