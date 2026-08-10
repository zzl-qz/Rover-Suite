package com.rover.common.util;

import java.util.concurrent.ExecutorService;

/**
 * Author: Daylight
 * Created: 2026-08-08 10:34:00
 * Description: 提供带命名前缀的线程池创建工具
 *
 * 这个类是什么：线程池工厂静态工具。
 * 核心职责：统一按「固定线程数 + 命名前缀」创建线程池，使线程名可读，
 * 便于 jstack/监控按线程名定位具体模块。
 * 被谁用：rover-gateway/rover-registry 等需要自建线程池的模块。
 */
public final class ThreadPoolUtil {

    /** 工具类不允许实例化 */
    private ThreadPoolUtil() {
    }

    /**
     * 创建固定大小、带命名前缀线程的线程池(实现待补全)。
     *
     * @param nThreads   线程数
     * @param namePrefix 线程名前缀
     * @return 已就绪的线程池
     * @throws UnsupportedOperationException 尚未实现
     */
    public static ExecutorService newFixedThreadPool(int nThreads, String namePrefix) {
        throw new UnsupportedOperationException("TODO");
    }
}
