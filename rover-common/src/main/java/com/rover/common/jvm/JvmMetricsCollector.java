package com.rover.common.jvm;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Author: Daylight
 * Description: JVM 运行时指标采集（两组件通用，零依赖）。
 * 只读 JDK MXBean，不进转发/心跳热路径；有人拉管理口才采一次。
 */
public final class JvmMetricsCollector {

    private static final long BYTES_PER_MEBIBYTE = 1024L * 1024L;

    private JvmMetricsCollector() {
    }

    /**
     * 采集当前 JVM 运行时快照。
     *
     * @return 堆/非堆/分代/线程/GC/CPU/运行时长
     */
    public static Map<String, Object> collect() {
        Map<String, Object> jvm = new LinkedHashMap<>();

        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        MemoryUsage heap = memory.getHeapMemoryUsage();
        MemoryUsage nonHeap = memory.getNonHeapMemoryUsage();

        double heapUsedMb = bytesToMb(heap.getUsed());
        double heapCommittedMb = bytesToMb(heap.getCommitted());
        double heapMaxMb = bytesToMb(heap.getMax());
        double nonHeapUsedMb = bytesToMb(nonHeap.getUsed());

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

        GenerationUsage generation = generationUsage();

        jvm.put("heapUsedMb", heapUsedMb);
        jvm.put("heapCommittedMb", heapCommittedMb);
        jvm.put("heapMaxMb", heapMaxMb);
        jvm.put("heapUsedPercent", heapMaxMb > 0 ? round2(heapUsedMb * 100.0 / heapMaxMb) : 0);
        jvm.put("nonHeapUsedMb", nonHeapUsedMb);
        jvm.put("youngUsedMb", generation.youngUsedMb);
        jvm.put("oldUsedMb", generation.oldUsedMb);
        jvm.put("threadCount", threads.getThreadCount());
        jvm.put("daemonThreadCount", threads.getDaemonThreadCount());
        jvm.put("gcCount", gcCount);
        jvm.put("gcTimeMillis", gcTimeMillis);
        jvm.put("processCpuPercent", processCpuPercent());
        jvm.put("uptimeSeconds", uptimeMillis / 1000);
        jvm.put("pid", runtime.getPid());
        return jvm;
    }

    /** 快捷方法：直接获取 JVM 堆内存使用情况（供需要独立取值的场景）。 */
    public static Map<String, Object> heapSnapshot() {
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        Map<String, Object> heapInfo = new LinkedHashMap<>();
        heapInfo.put("usedMb", bytesToMb(heap.getUsed()));
        heapInfo.put("committedMb", bytesToMb(heap.getCommitted()));
        heapInfo.put("maxMb", bytesToMb(heap.getMax()));
        heapInfo.put("usedPercent", bytesToMb(heap.getMax()) > 0
                ? round2(bytesToMb(heap.getUsed()) * 100.0 / bytesToMb(heap.getMax())) : 0);
        return heapInfo;
    }

    /** 进程 CPU%，读不到时返回 0。HotSpot 大约 1 秒刷新一次。 */
    private static double processCpuPercent() {
        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        if (os instanceof com.sun.management.OperatingSystemMXBean sunOs) {
            double load = sunOs.getProcessCpuLoad();
            if (load >= 0) {
                return round2(load * 100.0);
            }
        }
        return 0;
    }

    /** 按内存池名字拆年轻代 / 老年代，认不出的池忽略。 */
    private static GenerationUsage generationUsage() {
        double young = 0;
        double old = 0;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            MemoryUsage usage = pool.getUsage();
            if (usage == null) {
                continue;
            }
            String name = pool.getName().toLowerCase(Locale.ROOT);
            if (isOldGen(name)) {
                old += usage.getUsed();
            } else if (isYoungGen(name)) {
                young += usage.getUsed();
            }
        }
        GenerationUsage result = new GenerationUsage();
        result.youngUsedMb = bytesToMb((long) young);
        result.oldUsedMb = bytesToMb((long) old);
        return result;
    }

    private static boolean isYoungGen(String name) {
        return name.contains("eden")
                || name.contains("survivor")
                || name.contains("young")
                || name.contains("nursery");
    }

    private static boolean isOldGen(String name) {
        return name.contains("old") || name.contains("tenured");
    }

    private static double bytesToMb(long bytes) {
        if (bytes <= 0) {
            return 0;
        }
        return round1(bytes / (double) BYTES_PER_MEBIBYTE);
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static final class GenerationUsage {
        double youngUsedMb;
        double oldUsedMb;
    }
}
