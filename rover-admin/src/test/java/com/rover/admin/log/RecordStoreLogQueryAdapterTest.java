package com.rover.admin.log;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.rover.agent.core.port.LogEntry;
import com.rover.agent.core.port.LogRequest;
import com.rover.common.log.JdbcRecordStore;
import com.rover.common.log.RecordStore;
import com.rover.common.log.RecordType;
import java.util.List;
import org.junit.jupiter.api.Test;

class RecordStoreLogQueryAdapterTest {

    @Test
    void adaptsRecordsToAgentLogEntries() {
        RecordStore store = new JdbcRecordStore("./target/test-adapter-" + System.nanoTime());
        store.log(com.rover.common.log.Record.of(RecordType.CONFIG_CHANGE, "/api/x", "p"));
        store.flush();

        RecordStoreLogQueryAdapter adapter = new RecordStoreLogQueryAdapter(store);
        List<LogEntry> entries = adapter.query(
                new LogRequest("/api/x", null, null, List.of("CONFIG_CHANGE"), 100));

        assertEquals(1, entries.size());
        assertEquals("/api/x", entries.get(0).target());
        assertEquals("CONFIG_CHANGE", entries.get(0).type());

        store.close();
    }
}
