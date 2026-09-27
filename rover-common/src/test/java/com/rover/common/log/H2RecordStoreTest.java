package com.rover.common.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class H2RecordStoreTest {

    @Test
    void logAndQueryAndPurge() throws Exception {
        String dbPath = "./target/test-logs/rover-" + System.nanoTime();
        RecordStore store = new H2RecordStore(dbPath);
        long now = System.currentTimeMillis();

        store.log(Record.of(RecordType.CONFIG_CHANGE, "/api/order", "{\"action\":\"saveRoute\"}"));
        store.log(new Record(now - 10_000, RecordType.HEARTBEAT, "svc", "hb"));
        store.flush();

        // 全量
        List<Record> all = store.query(LogQuery.of(null, null, null, null, 100));
        assertTrue(all.size() >= 2, "应至少写入 2 条");

        // 按 target 过滤
        List<Record> byTarget = store.query(LogQuery.of(null, null, "/api/order", null, 100));
        assertEquals(1, byTarget.size());

        // 按类型过滤
        List<Record> byType = store.query(LogQuery.of(null, null, null, List.of(RecordType.HEARTBEAT), 100));
        assertEquals(1, byType.size());

        // 时间区间：只保留最近 5 秒
        long removed = store.purgeOlderThan(now - 5_000);
        assertTrue(removed >= 1, "应至少清理 1 条旧记录");

        // 清理后心跳(早于 now-5s)应消失，配置变更(约 now)应仍在
        List<Record> afterPurge = store.query(LogQuery.of(null, null, null, null, 100));
        assertEquals(1, afterPurge.size());
        assertEquals(RecordType.CONFIG_CHANGE, afterPurge.get(0).type());

        store.close();
    }

    @Test
    void queueFullDropsBestEffortWithoutBlocking() {
        // 极小队列，验证 offer 失败不抛异常（best-effort）
        RecordStore store = new H2RecordStore("./target/test-logs/drop-" + System.nanoTime(), 1);
        for (int i = 0; i < 5000; i++) {
            store.log(Record.of(RecordType.HEARTBEAT, "svc", "hb" + i));
        }
        store.flush();
        store.close();
        // 不要求全部落盘，只验证流程不抛异常、不阻塞
        assertTrue(true);
    }
}
