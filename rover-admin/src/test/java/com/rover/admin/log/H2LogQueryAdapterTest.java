package com.rover.admin.log;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.rover.agent.core.port.LogEntry;
import com.rover.agent.core.port.LogRequest;
import com.rover.common.log.H2RecordStore;
import com.rover.common.log.RecordStore;
import com.rover.common.log.RecordType;
import java.util.List;
import org.junit.jupiter.api.Test;

class H2LogQueryAdapterTest {

    @Test
    void adaptsRecordsToAgentLogEntries() {
        RecordStore store = new H2RecordStore("./target/test-adapter-" + System.nanoTime());
        store.log(com.rover.common.log.Record.of(RecordType.CONFIG_CHANGE, "/api/x", "p"));
        store.flush();

        H2LogQueryAdapter adapter = new H2LogQueryAdapter(store);
        List<LogEntry> entries = adapter.query(
                new LogRequest("/api/x", null, null, List.of("CONFIG_CHANGE"), 100));

        assertEquals(1, entries.size());
        assertEquals("/api/x", entries.get(0).target());
        assertEquals("CONFIG_CHANGE", entries.get(0).type());

        store.close();
    }
}
