package com.rover.admin.config;

import com.rover.admin.log.H2LogQueryAdapter;
import com.rover.agent.core.port.LogQueryPort;
import com.rover.common.concurrent.PeriodicTask;
import com.rover.common.log.H2RecordStore;
import com.rover.common.log.RecordStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 落盘记录库（H2）装配根：{@link RecordStore} 异步写入/查询、{@link LogQueryPort} 只读端口适配、
 * retention 定时清理。放 config 包（非 web 包），不影响 {@code @WebMvcTest} 切片。
 */
@Configuration(proxyBeanMethods = false)
@Slf4j
public class LogStoreConfiguration {

    @Bean
    public RecordStore recordStore(AdminProperties props) {
        return new H2RecordStore(props.getLogStorePath(), props.getLogQueueCapacity(), props.getLogCriticalCapacity());
    }

    @Bean
    public LogQueryPort logQueryPort(RecordStore store) {
        return new H2LogQueryAdapter(store);
    }

    /**
     * 保留策略：每小时滚动清理早于 retentionDays 天的记录。
     * 返回 {@link PeriodicTask}（AutoCloseable），Spring 关闭时自动停止调度。
     */
    @Bean
    public PeriodicTask logRetentionTask(RecordStore store, AdminProperties props) {
        PeriodicTask task = new PeriodicTask("rover-log-retention");
        long retentionMs = Math.max(1, props.getLogRetentionDays()) * 24L * 3_600_000L;
        task.start(() -> {
            long cutoff = System.currentTimeMillis() - retentionMs;
            long removed = store.purgeOlderThan(cutoff);
            if (removed > 0) {
                log.info("日志保留策略: 清理 {} 条早于 {} 的记录", removed, cutoff);
            }
        }, 60_000L, 3_600_000L);
        return task;
    }
}
