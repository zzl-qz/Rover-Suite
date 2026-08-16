package com.rover.common.jvm;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Author: Daylight
 * Description: JVM 运行时指标采集（两组件通用，零依赖）。
 * 数据来源 JDK 自带 ManagementFactory，不引入任何外部库。
 */
public final class JvmMetricsCollector {

    private JvmMetricsCollector() {
    }

    /**
     * 采集当前 JVM 运行时快照。
     *
     * @return 指标 Map：堆内存/非堆/线程/GC/运行时长
     */
    public static Map<String, Object> collect() {
        Map<String, Object> jvm = new LinkedHashMap<>();

        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        MemoryUsage heap = memory.getHeapMemoryUsage();
        MemoryUsage nonHeap = memory.getNonHeapMemoryUsage();

        long heapUsedMb = bytesToMb(heap.getUsed());
        long heapMaxMb = bytesToMb(heap.getMax());
        long nonHeapUsedMb = bytesToMb(nonHeap.getUsed());

        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
        long uptimeMillis = runtime.getUptime();

        long gcCount = 0;
        long gcTimeMillis = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            long count = gc.getCollectionCount();
            long time = gc.getCollectionTime();
            if (count >= 0) {
                gcCount += count;
            }
            if (time >= 0) {
                gcTimeMillis += time;
            }
        }

        jvm.put("heapUsedMb", heapUsedMb);
        jvm.put("heapMaxMb", heapMaxMb);
        jvm.put("heapUsedPercent", heapMaxMb > 0 ? round2(heapUsedMb * 100.0 / heapMaxMb) : 0);
        jvm.put("nonHeapUsedMb", nonHeapUsedMb);
        jvm.put("threadCount", threads.getThreadCount());
        jvm.put("daemonThreadCount", threads.getDaemonThreadCount());
        jvm.put("gcCount", gcCount);
        jvm.put("gcTimeMillis", gcTimeMillis);
        jvm.put("uptimeSeconds", uptimeMillis / 1000);
        jvm.put("pid", runtime.getPid());
        return jvm;
    }

    /** 快捷方法：直接获取 JVM 堆内存使用情况（供需要独立取值的场景）。 */
    public static Map<String, Object> heapSnapshot() {
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        Map<String, Object> heapInfo = new LinkedHashMap<>();
        heapInfo.put("usedMb", bytesToMb(heap.getUsed()));
        heapInfo.put("maxMb", bytesToMb(heap.getMax()));
        heapInfo.put("usedPercent", bytesToMb(heap.getMax()) > 0
                ? round2(bytesToMb(heap.getUsed()) * 100.0 / bytesToMb(heap.getMax())) : 0);
        return heapInfo;
    }

    private static long bytesToMb(long bytes) {
        return bytes < 0 ? 0 : bytes / (1024 * 1024);
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    /** 供内部使用避免未使用告警。 */
    @SuppressWarnings("unused")
    private static List<GarbageCollectorMXBean> gcBeans() {
        return ManagementFactory.getGarbageCollectorMXBeans();
    }
}
