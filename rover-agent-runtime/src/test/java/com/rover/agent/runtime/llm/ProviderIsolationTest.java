package com.rover.agent.runtime.llm;

import java.net.ConnectException;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProviderIsolationTest {
    @Test void failuresOpenCircuitThenOneProbeRestoresItWithoutBlockingOtherProviders() {
        AtomicLong clock=new AtomicLong(); var policy=new ProviderIsolation(new ProviderIsolation.Policy(2,30,3),clock::get);
        policy.acquire("bad").failure(new ConnectException()); policy.acquire("bad").failure(new ConnectException());
        assertEquals("CIRCUIT_OPEN",assertThrows(ProviderIsolation.Rejected.class,()->policy.acquire("bad")).kind());
        policy.acquire("other").success(); clock.set(30_000_000_001L);
        var probe=policy.acquire("bad"); assertThrows(ProviderIsolation.Rejected.class,()->policy.acquire("bad"));
        probe.success(); policy.acquire("bad").success();
    }
    @Test void bulkheadRejectsWithoutQueueAndDoesNotPoisonTheCircuit() {
        var policy=new ProviderIsolation(new ProviderIsolation.Policy(1,30,1));
        var active=policy.acquire("p"); assertEquals("PROVIDER_BUSY",assertThrows(ProviderIsolation.Rejected.class,()->policy.acquire("p")).kind());
        active.close(); policy.acquire("p").success();
    }
    @Test void lateSuccessCannotCloseANewCircuitOrReleaseItsProbe() {
        AtomicLong clock=new AtomicLong(); var policy=new ProviderIsolation(new ProviderIsolation.Policy(1,1,4),clock::get);
        var old=policy.acquire("p");policy.acquire("p").failure(new ConnectException()); clock.set(1_000_000_001L);
        var probe=policy.acquire("p");old.success();assertThrows(ProviderIsolation.Rejected.class,()->policy.acquire("p"));
        probe.failure(new ConnectException()); assertThrows(ProviderIsolation.Rejected.class,()->policy.acquire("p"));
    }
    @Test void lateFailureCannotReopenAfterASuccessfulProbe() {
        AtomicLong clock=new AtomicLong(); var policy=new ProviderIsolation(new ProviderIsolation.Policy(1,1,4),clock::get);
        var old=policy.acquire("p");policy.acquire("p").failure(new ConnectException());clock.set(1_000_000_001L);
        policy.acquire("p").success();old.failure(new ConnectException());policy.acquire("p").success();
    }
    @Test void cancellationAndOutputContractFailureAreNotProviderOutages() {
        var policy=new ProviderIsolation(new ProviderIsolation.Policy(1,30,1));
        policy.acquire("p").failure(new IllegalArgumentException("invalid JSON"));
        policy.acquire("p").failure(new com.rover.agent.runtime.task.AgentRunBudget.LimitExceeded("budget"));
        policy.acquire("p").success();
    }
    @Test void exceptionCyclesAndMessageTextCannotForgeAnHttpStatus() {
        var a=new RuntimeException("HTTP 503 token=secret");var b=new RuntimeException(a);a.initCause(b);
        assertEquals("ERROR",ModelFailures.kind(a));
    }
    @Test void authenticationFailsFastAndRateLimitHonorsLongerRetryAfter() {
        var auth=new ProviderIsolation(new ProviderIsolation.Policy(3,30,2));auth.acquire("p").failure(new ModelProviderFailure(401,0));
        assertThrows(ProviderIsolation.Rejected.class,()->auth.acquire("p"));
        AtomicLong clock=new AtomicLong();var rate=new ProviderIsolation(new ProviderIsolation.Policy(1,30,2),clock::get);
        rate.acquire("p").failure(new ModelProviderFailure(429,120));clock.set(31_000_000_000L);assertThrows(ProviderIsolation.Rejected.class,()->rate.acquire("p"));
        clock.set(121_000_000_000L);rate.acquire("p").success();
        assertEquals("INVALID_REQUEST",ModelFailures.kind(new ModelProviderFailure(400,0)));
        assertEquals(3600,ModelFailures.parseRetryAfter("999999"));assertEquals(0,ModelFailures.parseRetryAfter("not-a-delay"));
    }
}
