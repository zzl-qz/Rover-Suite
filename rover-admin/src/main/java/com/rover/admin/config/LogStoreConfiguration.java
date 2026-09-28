package com.rover.admin.config;

import com.rover.admin.log.TelemetryCollector;
import com.rover.admin.service.AdminConfigService;
import com.rover.common.concurrent.PeriodicTask;
import com.rover.common.log.JdbcRecordStore;
import com.rover.common.log.RecordStore;
import com.rover.common.log.RecordType;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 落盘记录库（H2）装配根：{@link RecordStore} 异步写入/查询、遥测采集、retention 定时清理。
 * 只读查询端口的适配由 {@code RecordStoreLogQueryAdapter}（@Component）提供，不在此重复装配。
 * 放 config 包（非 web 包），不影响 {@code @WebMvcTest} 切片。
 */
@Configuration(proxyBeanMethods = false)
@Slf4j
public class LogStoreConfiguration {

    /** 诊断证据类记录（高优、保留期长）。 */
    private static final List<RecordType> CRITICAL_TYPES =
            Arrays.stream(RecordType.values()).filter(RecordType::isCritical).collect(Collectors.toList());
    /** 遥测类记录（量大、保留期短）。 */
    private static final List<RecordType> TELEMETRY_TYPES =
            Arrays.stream(RecordType.values()).filter(t -> !t.isCritical()).collect(Collectors.toList());

    @Bean
    public RecordStore recordStore(AdminProperties props) {
        return new JdbcRecordStore(props.getLogStorePath(), props.getLogQueueCapacity(), props.getLogCriticalCapacity());
    }

    /**
     * 遥测采集器：周期拉取 Gateway / Nameserver 指标与慢链路写入落盘库。
     * 返回 AutoCloseable，Spring 关闭时自动停止调度线程。
     */
    @Bean
    public TelemetryCollector telemetryCollector(AdminConfigService service, RecordStore store, AdminProperties props) {
        return new TelemetryCollector(service, store, props);
    }

    /**
     * 保留策略：每小时按类型拆分清理——遥测短（默认 3 天）、诊断证据长（默认 30 天）。
     * 返回 {@link PeriodicTask}（AutoCloseable），Spring 关闭时自动停止调度。
     */
    @Bean
    public PeriodicTask logRetentionTask(RecordStore store, AdminProperties props) {
        PeriodicTask task = new PeriodicTask("rover-log-retention");
        long criticalMs = Math.max(1, props.getLogRetentionDays()) * 24L * 3_600_000L;
        long telemetryMs = Math.max(1, props.getLogTelemetryRetentionDays()) * 24L * 3_600_000L;
        task.start(() -> {
            long now = System.currentTimeMillis();
            long removed = store.purgeOlderThan(now - criticalMs, CRITICAL_TYPES);
            removed += store.purgeOlderThan(now - telemetryMs, TELEMETRY_TYPES);
            if (removed > 0) {
                log.info("日志保留策略: 清理 {} 条过期记录 (审计{}天 / 遥测{}天)", removed,
                        props.getLogRetentionDays(), props.getLogTelemetryRetentionDays());
            }
        }, 60_000L, 3_600_000L);
        return task;
    }
}
