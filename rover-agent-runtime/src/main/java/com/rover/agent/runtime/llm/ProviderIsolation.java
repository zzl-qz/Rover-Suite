package com.rover.agent.runtime.llm;

import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/** 单 Admin 内按端点与凭证隔离；没有等待队列，半开时只放行一个探测。 */
public final class ProviderIsolation {
    public record Policy(int failureThreshold,int cooldownSeconds,int maxConcurrent) {
        public Policy { if (failureThreshold<1 || failureThreshold>20 || cooldownSeconds<1 || cooldownSeconds>3600 || maxConcurrent<1 || maxConcurrent>32) { throw new IllegalArgumentException("模型隔离策略越界"); } }
        public static Policy defaults() { return new Policy(3,30,4); }
    }
    private final Policy policy;
    private final LongSupplier clock;
    private final Map<String,State> states=new HashMap<>();
    public ProviderIsolation(Policy policy) { this(policy,System::nanoTime); }
    ProviderIsolation(Policy policy,LongSupplier clock) { this.policy=policy;this.clock=clock; }
    public synchronized Lease acquire(String key) {
        if (!states.containsKey(key) && states.size()>=64) { throw new Rejected("ISOLATION_CAPACITY"); }
        State state=states.computeIfAbsent(key,k->new State());
        if (state.open && clock.getAsLong()-state.reopenAt<0) { throw new Rejected("CIRCUIT_OPEN"); }
        if (state.open && state.probing) { throw new Rejected("CIRCUIT_OPEN"); }
        if (state.inflight>=policy.maxConcurrent()) { throw new Rejected("PROVIDER_BUSY"); }
        boolean probe=state.open;
        if (probe) { state.probing=true; }
        state.inflight++; return new Lease(state,probe,state.generation);
    }
    private static final class State { int failures;int inflight;long reopenAt;long generation;boolean open;boolean probing; }
    public final class Lease implements AutoCloseable {
        private final State state; private final boolean probe; private final long generation; private boolean done;
        private Lease(State state,boolean probe,long generation) { this.state=state;this.probe=probe;this.generation=generation; }
        public void success() { finish(null); }
        public void failure(Throwable failure) { finish(failure); }
        private void finish(Throwable failure) {
            synchronized (ProviderIsolation.this) {
                if (done) { return; } done=true; state.inflight--;
                if (generation!=state.generation) { return; }
                if (probe) { state.probing=false; }
                if (failure==null) {
                    // 旧请求的迟到成功不能关闭其他请求新打开的熔断器。
                    if (!state.open || probe) { state.failures=0;state.open=false;if(probe){state.generation++;} }
                    return;
                }
                String kind=ModelFailures.kind(failure);
                if (kind.equals("AUTHENTICATION") || kind.equals("TIMEOUT") || kind.equals("RATE_LIMITED")
                        || kind.equals("UNAVAILABLE") || kind.equals("SERVER_ERROR")) {
                    state.failures++;
                    if (probe || kind.equals("AUTHENTICATION") || state.failures>=policy.failureThreshold()) {
                        int delay=Math.max(policy.cooldownSeconds(),ModelFailures.retryAfterSeconds(failure));
                        state.open=true;state.reopenAt=clock.getAsLong()+delay*1_000_000_000L;
                        state.generation++;
                    }
                } else if (probe) {
                    // 非瞬态参数错误证明服务器可达，但本次输出仍失败。
                    state.open=false;state.failures=0;
                }
            }
        }
        public void close() { finish(new java.util.concurrent.CancellationException()); }
    }
    public static final class Rejected extends RuntimeException {
        private final String kind;
        public Rejected(String kind) { super(kind);this.kind=kind; }
        public String kind() { return kind; }
    }
}
