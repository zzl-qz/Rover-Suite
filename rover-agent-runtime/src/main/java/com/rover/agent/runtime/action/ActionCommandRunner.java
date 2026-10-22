package com.rover.agent.runtime.action;

import com.rover.agent.core.repository.ActionCommandRepository;
import jakarta.annotation.PreDestroy;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;

/** Bounded, single-Admin worker for durable external action commands. */
public final class ActionCommandRunner implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ActionCommandRunner.class);
    private final ActionCommandRepository commands;
    private final AgentActionService actions;
    private final ThreadPoolExecutor workers;
    private final ScheduledThreadPoolExecutor poller;
    private final Set<String> scheduled = ConcurrentHashMap.newKeySet();
    private final long pollMillis;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean stopped = new AtomicBoolean();

    public ActionCommandRunner(ActionCommandRepository commands, AgentActionService actions,
                               int workerThreads, int queueCapacity, long pollMillis) {
        if (workerThreads < 1 || workerThreads > 16 || queueCapacity < 1 || queueCapacity > 1000
                || pollMillis < 50 || pollMillis > 10_000) {
            throw new IllegalArgumentException("action execution worker settings are outside supported bounds");
        }
        this.commands = commands;
        this.actions = actions;
        this.pollMillis = pollMillis;
        this.workers = new ThreadPoolExecutor(workerThreads, workerThreads, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), runnable -> {
                    Thread thread = new Thread(runnable, "rover-agent-action-worker");
                    thread.setDaemon(true);
                    return thread;
                });
        this.poller = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "rover-agent-action-poller");
            thread.setDaemon(true);
            return thread;
        });
        this.poller.setRemoveOnCancelPolicy(true);
    }

    @EventListener(ContextRefreshedEvent.class)
    public void start() {
        if (started.compareAndSet(false, true) && !stopped.get()) {
            poller.scheduleWithFixedDelay(this::poll, 0, pollMillis, TimeUnit.MILLISECONDS);
        }
    }

    private void poll() {
        if (stopped.get() || workers.isShutdown()) { return; }
        try {
            for (ActionCommandRepository.Job job : commands.pending(System.currentTimeMillis(), 200)) {
                schedule(job);
            }
        } catch (RuntimeException failure) {
            log.error("持久动作队列读取失败，后台执行暂停并等待恢复", failure);
        }
    }

    private void schedule(ActionCommandRepository.Job pending) {
        String commandId = pending.command().commandId();
        if (!scheduled.add(commandId)) { return; }
        try {
            workers.execute(() -> run(pending));
        } catch (RejectedExecutionException full) {
            scheduled.remove(commandId);
        }
    }

    private void run(ActionCommandRepository.Job pending) {
        String commandId = pending.command().commandId();
        try {
            ActionCommandRepository.Job claimed = commands.claim(commandId, pending.attempt()).orElse(null);
            if (claimed == null) { return; }
            actions.executeCommand(claimed.command());
            commands.finish(commandId, claimed.attempt());
        } catch (RuntimeException failure) {
            long delaySeconds = failure instanceof ActionResourceBusyException
                    ? 1 : Math.min(60, 1L << Math.min(6, pending.attempt() + 1));
            try {
                commands.retry(commandId, pending.attempt() + 1,
                        System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(delaySeconds),
                        safeMessage(failure));
            } catch (RuntimeException persistenceFailure) {
                failure.addSuppressed(persistenceFailure);
            }
            log.error("持久动作命令执行失败，将按原命令重试: {}", commandId, failure);
        } finally {
            scheduled.remove(commandId);
        }
    }

    private static String safeMessage(Throwable failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) { return failure.getClass().getSimpleName(); }
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }

    @EventListener(ContextClosedEvent.class)
    @PreDestroy
    public void close() {
        if (!stopped.compareAndSet(false, true)) { return; }
        poller.shutdownNow();
        workers.shutdown();
        try {
            if (!workers.awaitTermination(5, TimeUnit.SECONDS)) { workers.shutdownNow(); }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            workers.shutdownNow();
        }
    }
}
