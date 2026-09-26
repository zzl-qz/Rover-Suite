package com.rover.agent.core.investigation;

import java.util.List;

/**
 * 一条证据的表述结果。
 *
 * 除了给人看的事实与判断边界，还带上这份证据的统计口径：窗口秒数与样本量。
 * 「样本不足就不能下结论」是硬规则，因此口径必须与证据同时产出，不能由调用方各自估计。
 * {@code windowSeconds} 为 0 表示时点快照（如配置），没有统计窗口。
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