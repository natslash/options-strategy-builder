package com.natslash.options_strategy_builder.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * Rate-limits outbound IBKR requests to avoid pacing violations.
 *
 * <p>Callers {@link #submit submit()} work as a {@code Supplier<CompletableFuture<T>>}.
 * The supplier must return a CompletableFuture immediately (non-blocking).
 * A single-thread {@link ScheduledExecutorService} drains one task from the
 * internal queue every {@value #RATE_MS} ms — 50 req/s — without ever sleeping
 * a caller thread.
 *
 * <p>Lifecycle: {@link #start()} begins the drain loop on {@code @PostConstruct};
 * {@link #stop()} shuts the scheduler down on {@code @PreDestroy}.
 */
@Slf4j
@Component
public class RateLimitedRequestManager {

    /** Spacing between dispatched requests in milliseconds (50 req/s). */
    static final int RATE_MS = 20;

    private final BlockingQueue<Runnable>  queue     = new LinkedBlockingQueue<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    // ── Lifecycle ──────────────────────────────────────────────

    @PostConstruct
    void start() {
        scheduler.scheduleAtFixedRate(this::drainOne, 0, RATE_MS, TimeUnit.MILLISECONDS);
        log.info("RateLimitedRequestManager started — {} req/s ({}ms spacing)",
                1000 / RATE_MS, RATE_MS);
    }

    @PreDestroy
    void stop() {
        scheduler.shutdown();
        log.info("RateLimitedRequestManager stopped");
    }

    // ── Public API ─────────────────────────────────────────────

    /**
     * Enqueues {@code work} for rate-limited execution and returns a promise
     * that mirrors the completion of {@code work}'s inner future.
     *
     * <p>{@code work} is called in the scheduler thread and <em>must not block</em>.
     * It should return a {@link CompletableFuture} immediately (e.g. from
     * {@link IbkrClientService#reqMktData}).
     *
     * @param work supplier that produces the async result
     * @param <T>  result type
     * @return a future that completes (normally or exceptionally) when the
     *         inner future produced by {@code work} completes
     */
    public <T> CompletableFuture<T> submit(Supplier<CompletableFuture<T>> work) {
        CompletableFuture<T> promise = new CompletableFuture<>();
        queue.offer(() -> {
            try {
                work.get().whenComplete((value, ex) -> {
                    if (ex != null) promise.completeExceptionally(ex);
                    else            promise.complete(value);
                });
            } catch (Exception e) {
                promise.completeExceptionally(e);
            }
        });
        return promise;
    }

    // ── Internal drain ─────────────────────────────────────────

    /**
     * Invoked every {@value #RATE_MS} ms by the scheduler.
     * Polls exactly one task and runs it. A no-op when the queue is empty.
     * Exceptions are caught to prevent the periodic task from being suppressed.
     */
    private void drainOne() {
        Runnable task = queue.poll();
        if (task == null) return;
        try {
            task.run();
        } catch (Exception e) {
            log.error("Unexpected exception in rate-limiter task", e);
        }
    }
}
