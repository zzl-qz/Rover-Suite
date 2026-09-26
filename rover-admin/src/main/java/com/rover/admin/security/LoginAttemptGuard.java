package com.rover.admin.security;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 登录失败限流：按来源地址做滑动窗口计数，超限后短时间内不再受理登录，
 * 免得控制台成为口令爆破的靶子。
 *
 * 只保留计数，不保留口令、也不记录被尝试的用户名。
 */
@Component
public class LoginAttemptGuard {

    private final int maxFailures;
    private final long windowMillis;
    private final Map<String, Deque<Long>> failures = new ConcurrentHashMap<>();

    public LoginAttemptGuard(AdminSecurityProperties properties) {
        this.maxFailures = Math.max(1, properties.getMaxLoginFailures());
        this.windowMillis = Duration.ofSeconds(Math.max(1, properties.getFailureWindowSeconds())).toMillis();
    }

    /** 该来源是否已被限流。 */
    public boolean isBlocked(String source) {
        Deque<Long> attempts = failures.get(source);
        if (attempts == null) {
            return false;
        }
        synchronized (attempts) {
            prune(attempts, System.currentTimeMillis());
            return attempts.size() >= maxFailures;
        }
    }

    /** 记一次失败。 */
    public void recordFailure(String source) {
        Deque<Long> attempts = failures.computeIfAbsent(source, key -> new ArrayDeque<>());
        synchronized (attempts) {
            long now = System.currentTimeMillis();
            prune(attempts, now);
            attempts.addLast(now);
        }
    }

    /** 登录成功后清空该来源的失败计数。 */
    public void reset(String source) {
        failures.remove(source);
    }

    /** 窗口内剩余可尝试次数，供提示。 */
    public int remaining(String source) {
        Deque<Long> attempts = failures.get(source);
        if (attempts == null) {
            return maxFailures;
        }
        synchronized (attempts) {
            prune(attempts, System.currentTimeMillis());
            return Math.max(0, maxFailures - attempts.size());
        }
    }

    private void prune(Deque<Long> attempts, long now) {
        while (!attempts.isEmpty() && now - attempts.peekFirst() > windowMillis) {
            attempts.pollFirst();
        }
    }
}