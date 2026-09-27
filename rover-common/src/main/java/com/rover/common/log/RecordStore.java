package com.rover.common.log;

import java.util.List;

/**
 * 追加式记录库（落盘日志/事件）的核心接口。
 *
 * 设计取舍：log() 必须非阻塞（调用方不应因写日志变慢）；query()/purgeOlderThan()
 * 用于历史证据检索与超期清理。flush() 供测试或优雅关闭前确保落盘。
 *
 * 继承 AutoCloseable：Spring 容器关闭时会自动调用 close()，触发写线程停止与残余队列刷盘，
 * 避免优雅停机时丢失内存中尚未落盘的记录。
 */
public interface RecordStore extends AutoCloseable {

    /** 追加一条记录。实现应保证非阻塞，队列满时 best-effort 丢弃并计数。 */
    void log(Record record);

    /** 按条件做时间区间查询，返回按时间倒序（最新在前）的记录。 */
    List<Record> query(LogQuery query);

    /** 删除 ts 早于 cutoffMillis 的记录，返回删除条数。 */
    long purgeOlderThan(long cutoffMillis);

    /**
     * 删除 ts 早于 cutoffMillis 且类型属于 {@code types} 的记录，返回删除条数。
     * 用于按数据类型拆分保留期（遥测短、审计长）；types 为空时不删任何记录。
     */
    long purgeOlderThan(long cutoffMillis, List<RecordType> types);

    /** 阻塞直到队列中待写记录全部落盘。 */
    void flush();

    /** 关闭：停止写线程并释放连接。 */
    void close();
}
