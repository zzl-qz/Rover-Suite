package com.rover.admin.log;

import com.rover.agent.core.port.LogEntry;
import com.rover.agent.core.port.LogQueryPort;
import com.rover.agent.core.port.LogRequest;
import com.rover.agent.core.port.SnapshotUnavailableException;
import com.rover.common.log.LogQuery;
import com.rover.common.log.Record;
import com.rover.common.log.RecordStore;
import com.rover.common.log.RecordType;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * 把底层 RecordStore 适配成 Agent 领域层的 {@link LogQueryPort}。
 * 类型字符串在此映射回存储层枚举（未知类型忽略，不抛错）。
 */
@Component
public class H2LogQueryAdapter implements LogQueryPort {

    private final RecordStore store;

    public H2LogQueryAdapter(RecordStore store) {
        this.store = store;
    }

    @Override
    public List<LogEntry> query(LogRequest request) {
        try {
            List<RecordType> types = null;
            if (request.types() != null && !request.types().isEmpty()) {
                types = new ArrayList<>();
                for (String t : request.types()) {
                    try {
                        // 工具描述里写的是 config_change 这类小写名，枚举是大写：统一按大写解析，
                        // 免得模型照抄描述传参时被当成「未知类型」静默忽略。
                        types.add(RecordType.valueOf(t.trim().toUpperCase(Locale.ROOT)));
                    } catch (IllegalArgumentException ignored) {
                        // 未知类型跳过
                    }
                }
            }
            List<Record> recs = store.query(new LogQuery(
                    request.from(), request.to(), request.target(), types, request.limit()));
            List<LogEntry> out = new ArrayList<>(recs.size());
            for (Record r : recs) {
                out.add(new LogEntry(r.ts(), r.type().name(), r.target(), r.payload()));
            }
            return out;
        } catch (Exception e) {
            throw new SnapshotUnavailableException("查询历史日志失败", e);
        }
    }
}
