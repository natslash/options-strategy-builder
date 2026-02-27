package com.natslash.options_strategy_builder.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.*;

class RateLimitedRequestManagerTest {

    private RateLimitedRequestManager manager;

    @BeforeEach
    void setUp() {
        manager = new RateLimitedRequestManager();
        manager.start();
    }

    @AfterEach
    void tearDown() {
        manager.stop();
    }

    // ── Happy path ─────────────────────────────────────────────

    @Test
    void submit_returnsResultFromInnerFuture() {
        CompletableFuture<String> result = manager.submit(
                () -> CompletableFuture.completedFuture("hello"));

        assertThat(result.join()).isEqualTo("hello");
    }

    @Test
    void submit_asyncInnerFuture_completesWhenInnerCompletes() {
        // Inner future is not yet complete when submit() is called.
        // Result must wait for inner — not complete until inner.complete() is called.
        CompletableFuture<Integer> inner = new CompletableFuture<>();
        CompletableFuture<Integer> result = manager.submit(() -> inner);

        // result is tied to inner — neither done yet (inner not completed)
        inner.complete(42);

        assertThat(result.join()).isEqualTo(42);
    }

    // ── Exception propagation ──────────────────────────────────

    @Test
    void submit_innerFutureFailure_propagatesExceptionally() {
        RuntimeException cause = new RuntimeException("inner failure");

        CompletableFuture<String> result = manager.submit(
                () -> CompletableFuture.failedFuture(cause));

        assertThatThrownBy(result::join)
                .isInstanceOf(CompletionException.class)
                .hasCause(cause);
    }

    @Test
    void submit_supplierThrows_completesResultExceptionally() {
        CompletableFuture<String> result = manager.submit(() -> {
            throw new RuntimeException("supplier blew up");
        });

        assertThatThrownBy(result::join)
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(RuntimeException.class)
                .hasMessageContaining("supplier blew up");
    }

    // ── Ordering ───────────────────────────────────────────────

    @Test
    void submit_tasksExecuteInSubmissionOrder() {
        List<Integer> executionOrder = new CopyOnWriteArrayList<>();
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (int i = 0; i < 5; i++) {
            final int seq = i;
            futures.add(manager.submit(() -> {
                executionOrder.add(seq);
                return CompletableFuture.completedFuture(null);
            }));
        }

        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();

        assertThat(executionOrder).containsExactly(0, 1, 2, 3, 4);
    }

    // ── Rate limiting ──────────────────────────────────────────

    @Test
    void submit_tasksAreSpacedByRateMs() {
        // 5 tasks dispatched at RATE_MS intervals → first to last spans ≥ 4 intervals.
        // Allow 5ms slack per interval to absorb OS scheduling jitter.
        int taskCount = 5;
        List<Long> fireTimestamps = new CopyOnWriteArrayList<>();
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (int i = 0; i < taskCount; i++) {
            futures.add(manager.submit(() -> {
                fireTimestamps.add(System.currentTimeMillis());
                return CompletableFuture.completedFuture(null);
            }));
        }

        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();

        long totalSpan     = fireTimestamps.get(taskCount - 1) - fireTimestamps.get(0);
        long minExpected   = (long) (taskCount - 1) * (RateLimitedRequestManager.RATE_MS - 5);
        assertThat(totalSpan)
                .as("5 tasks should span at least %dms (got %dms)", minExpected, totalSpan)
                .isGreaterThanOrEqualTo(minExpected);
    }

    // ── Idle-queue safety ──────────────────────────────────────

    @Test
    void drainOnEmptyQueue_doesNotThrowAndRemainsUsable() throws InterruptedException {
        // Let the scheduler tick several times on an empty queue — must not crash.
        Thread.sleep(3L * RateLimitedRequestManager.RATE_MS);

        // Manager must still be functional after idle ticks.
        CompletableFuture<String> result = manager.submit(
                () -> CompletableFuture.completedFuture("still alive"));

        assertThat(result.join()).isEqualTo("still alive");
    }
}
