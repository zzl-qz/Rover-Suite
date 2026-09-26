package com.rover.agent.runtime.task;

/**
 * 「AI 解读」增量订阅者：语义与 SSE 对齐 —— 订阅时先补发已产生的全文，随后持续接收增量，最后收到一次结束通知。
 *
 * 回调发生在调查线程内且持有任务锁，实现必须快速返回并自行消化异常：
 * 抛出异常会中断同批其它订阅者，也会让正在进行的这次解读降级。
 */
public interface AnalysisStreamListener {

    /** 补发订阅之前已产生的解读全文（可能为空）：晚连上的订阅者据此一次性对齐前缀。 */
    void onSnapshot(String text);

    /** 新产生的解读增量。 */
    void onDelta(String chunk);

    /** 解读结束：成功产出解释或已降级为规则诊断，之后不会再有增量。 */
    void onComplete();
}