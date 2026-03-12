package com.natslash.options_strategy_builder.service;

import com.natslash.options_strategy_builder.entity.*;
import com.natslash.options_strategy_builder.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaperTradingService {

    private final UserRepository userRepository;
    private final UserAccountRepository accountRepository;
    private final InstrumentRepository instrumentRepository;
    private final OrderRepository orderRepository;

    // Self-injection via @Lazy to route calls through the Spring proxy,
    // ensuring @Transactional on placeOrderFixed actually fires.
    @Lazy
    @Autowired
    private PaperTradingService self;

    // ═══════════════════════════════════════════════════════════════════════
    //  SETUP — seed a test user with €10,000 balance
    // ═══════════════════════════════════════════════════════════════════════

    @Transactional
    public Map<String, Object> setupTestUser() {
        // Clean previous experiment data.
        // deleteAllInBatch() runs a single DELETE FROM table SQL immediately,
        // avoiding Hibernate flush ordering issues that cause FK violations with deleteAll().
        orderRepository.deleteAllInBatch();
        accountRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();

        User user = new User();
        user.setUsername("experiment-user");
        user.setEmail("experiment@test.com");
        user = userRepository.save(user);

        UserAccount account = new UserAccount();
        account.setUser(user);
        account.setTotalBalance(new BigDecimal("10000.0000"));
        account.setAvailableBalance(new BigDecimal("10000.0000"));
        account = accountRepository.save(account);

        log.info("=== SETUP COMPLETE ===");
        log.info("User: {} (id={})", user.getUsername(), user.getId());
        log.info("Balance: €{}", account.getAvailableBalance());

        return Map.of(
            "userId", user.getId(),
            "balance", account.getAvailableBalance(),
            "version", account.getVersion() != null ? account.getVersion() : 0,
            "message", "Test user created with €10,000"
        );
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  EXPERIMENT 1: LOST UPDATE (BROKEN — no protection)
    //
    //  Two threads simultaneously place an order for €3,000 margin.
    //  Both read balance as €10,000, both deduct €3,000.
    //  Expected final balance: €4,000
    //  Actual final balance:   €7,000 (one deduction is lost!)
    // ═══════════════════════════════════════════════════════════════════════

    public Map<String, Object> runLostUpdateBroken() throws Exception {
        User user = userRepository.findByUsername("experiment-user")
                .orElseThrow(() -> new RuntimeException("Run /setup first"));

        BigDecimal marginRequired = new BigDecimal("3000.0000");
        Instrument instrument = instrumentRepository.findByActiveTrue().get(0);

        // Record starting state
        UserAccount before = accountRepository.findByUserId(user.getId()).orElseThrow();
        BigDecimal startingBalance = before.getAvailableBalance();

        log.info("=== EXPERIMENT 1: LOST UPDATE (BROKEN) ===");
        log.info("Starting balance: €{}", startingBalance);
        log.info("Each order requires: €{} margin", marginRequired);
        log.info("Expected final balance after 2 orders: €{}",
                startingBalance.subtract(marginRequired).subtract(marginRequired));

        // Use a latch so both threads start at the exact same moment
        CountDownLatch startGate = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        Future<String> futureA = executor.submit(() -> {
            startGate.await();
            return placeOrderBroken(user.getId(), instrument.getId(), marginRequired, "Thread-A");
        });

        Future<String> futureB = executor.submit(() -> {
            startGate.await();
            return placeOrderBroken(user.getId(), instrument.getId(), marginRequired, "Thread-B");
        });

        // Release both threads simultaneously
        startGate.countDown();

        String resultA = futureA.get(10, TimeUnit.SECONDS);
        String resultB = futureB.get(10, TimeUnit.SECONDS);
        executor.shutdown();

        // Check the damage

        UserAccount after = accountRepository.findByUserId(user.getId()).orElseThrow();

        log.info("=== RESULT ===");
        log.info("Thread-A: {}", resultA);
        log.info("Thread-B: {}", resultB);
        log.info("Final balance: €{}", after.getAvailableBalance());
        log.info("Expected:      €{}", startingBalance.subtract(marginRequired).subtract(marginRequired));

        boolean corrupted = after.getAvailableBalance()
                .compareTo(startingBalance.subtract(marginRequired).subtract(marginRequired)) != 0;

        if (corrupted) {
            log.error("💥 DATA CORRUPTION! Balance is €{} but should be €{}",
                    after.getAvailableBalance(),
                    startingBalance.subtract(marginRequired).subtract(marginRequired));
        }

        return Map.of(
            "startingBalance", startingBalance,
            "expectedFinalBalance", startingBalance.subtract(marginRequired).subtract(marginRequired),
            "actualFinalBalance", after.getAvailableBalance(),
            "threadA", resultA,
            "threadB", resultB,
            "corrupted", corrupted,
            "explanation", corrupted
                ? "LOST UPDATE! Both threads read €" + startingBalance
                  + ", both deducted €" + marginRequired
                  + ", second write overwrote the first."
                : "No corruption detected (race condition is timing-dependent — try again)"
        );
    }

    /**
     * BROKEN: No @Transactional. Read and write are separate DB transactions.
     * The gap between read and write is where corruption happens.
     */
    private String placeOrderBroken(Long userId, Long instrumentId, BigDecimal margin, String threadName) {
        // Step 1: READ the balance (own implicit transaction — commits immediately)
        UserAccount account = accountRepository.findByUserId(userId).orElseThrow();
        BigDecimal currentBalance = account.getAvailableBalance();
        log.info("[{}] Read balance: €{}", threadName, currentBalance);

        // Simulate processing delay — this widens the window for the race condition
        try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        // Step 2: CHECK if enough margin
        if (currentBalance.compareTo(margin) < 0) {
            log.info("[{}] REJECTED — insufficient balance", threadName);
            return "REJECTED";
        }

        // Step 3: WRITE the new balance using a raw UPDATE — bypasses @Version check.
        // Simulates naive code with no optimistic locking: second write silently overwrites first.
        BigDecimal newBalance = currentBalance.subtract(margin);
        accountRepository.updateBalanceDirectly(account.getId(), newBalance, LocalDateTime.now());
        log.info("[{}] Deducted €{}, wrote balance: €{}", threadName, margin, newBalance);

        return "FILLED at €" + margin + " margin";
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  EXPERIMENT 2: LOST UPDATE (FIXED — @Transactional + @Version)
    //
    //  Same scenario, but now with optimistic locking.
    //  One thread succeeds, the other gets OptimisticLockException.
    // ═══════════════════════════════════════════════════════════════════════

    public Map<String, Object> runLostUpdateFixed() throws Exception {
        User user = userRepository.findByUsername("experiment-user")
                .orElseThrow(() -> new RuntimeException("Run /setup first"));

        BigDecimal marginRequired = new BigDecimal("3000.0000");
        Instrument instrument = instrumentRepository.findByActiveTrue().get(0);

        UserAccount before = accountRepository.findByUserId(user.getId()).orElseThrow();
        BigDecimal startingBalance = before.getAvailableBalance();

        log.info("=== EXPERIMENT 2: LOST UPDATE (FIXED with @Version) ===");
        log.info("Starting balance: €{}", startingBalance);
        log.info("Account version: {}", before.getVersion());

        CountDownLatch startGate = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        Future<String> futureA = executor.submit(() -> {
            startGate.await();
            return self.placeOrderFixed(user.getId(), instrument.getId(), marginRequired, "Thread-A");
        });

        Future<String> futureB = executor.submit(() -> {
            startGate.await();
            return self.placeOrderFixed(user.getId(), instrument.getId(), marginRequired, "Thread-B");
        });

        startGate.countDown();

        // ObjectOptimisticLockingFailureException fires at commit time (after placeOrderFixed
        // returns), so it can't be caught inside the @Transactional method. It arrives here
        // wrapped in ExecutionException — that's the correct place to intercept it.
        String resultA = resolveOrderResult(futureA, "Thread-A");
        String resultB = resolveOrderResult(futureB, "Thread-B");
        executor.shutdown();

        UserAccount after = accountRepository.findByUserId(user.getId()).orElseThrow();

        log.info("=== RESULT ===");
        log.info("Thread-A: {}", resultA);
        log.info("Thread-B: {}", resultB);
        log.info("Final balance: €{}", after.getAvailableBalance());
        log.info("Account version: {}", after.getVersion());

        boolean oneRejected = resultA.contains("CONFLICT") || resultB.contains("CONFLICT");

        return Map.of(
            "startingBalance", startingBalance,
            "finalBalance", after.getAvailableBalance(),
            "version", after.getVersion(),
            "threadA", resultA,
            "threadB", resultB,
            "oneRejected", oneRejected,
            "explanation", oneRejected
                ? "SUCCESS! @Version detected the stale read. One order filled, one rejected. Balance is correct."
                : "Both succeeded (race condition timing — try again, or increase the sleep)"
        );
    }

    /**
     * FIXED: @Transactional ensures atomicity.
     * @Version on UserAccount causes OptimisticLockException when the
     * second thread tries to write with a stale version number.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public String placeOrderFixed(Long userId, Long instrumentId, BigDecimal margin, String threadName) {
        // Step 1: READ balance (inside transaction)
        UserAccount account = accountRepository.findByUserId(userId).orElseThrow();
        BigDecimal currentBalance = account.getAvailableBalance();
        log.info("[{}] Read balance: €{} (version={})", threadName, currentBalance, account.getVersion());

        // Simulate processing delay
        try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        // Step 2: CHECK margin
        if (currentBalance.compareTo(margin) < 0) {
            log.info("[{}] REJECTED — insufficient balance", threadName);
            return "REJECTED — insufficient balance";
        }

        // Step 3: WRITE new balance.
        // If another thread already committed, the version won't match and Hibernate throws
        // ObjectOptimisticLockingFailureException at commit time — AFTER this method returns.
        // That exception is caught in resolveOrderResult() via the Future's ExecutionException.
        account.setAvailableBalance(currentBalance.subtract(margin));
        account.setUpdatedAt(LocalDateTime.now());
        accountRepository.save(account);

        log.info("[{}] Deducted €{}, wrote balance: €{} (version will increment on commit)",
                threadName, margin, account.getAvailableBalance());

        return "FILLED at €" + margin + " margin";
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  CHECK BALANCE — inspect current state
    // ═══════════════════════════════════════════════════════════════════════

    public Map<String, Object> getBalance() {
        User user = userRepository.findByUsername("experiment-user")
                .orElseThrow(() -> new RuntimeException("Run /setup first"));
        UserAccount account = accountRepository.findByUserId(user.getId()).orElseThrow();

        return Map.of(
            "balance", account.getAvailableBalance(),
            "totalBalance", account.getTotalBalance(),
            "version", account.getVersion() != null ? account.getVersion() : "N/A",
            "updatedAt", account.getUpdatedAt().toString()
        );
    }

    // ── helper ──────────────────────────────────────────────────────────

    /**
     * Resolves a Future result, treating ExecutionException caused by
     * ObjectOptimisticLockingFailureException as a CONFLICT outcome.
     * Commit-time optimistic lock exceptions can't be caught inside the
     * @Transactional method — they arrive here via ExecutionException.
     */
    private String resolveOrderResult(Future<String> future, String threadName) {
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            while (cause != null) {
                if (cause instanceof jakarta.persistence.OptimisticLockException
                        || cause instanceof org.hibernate.StaleObjectStateException) {
                    log.warn("[{}] ⚡ CONFLICT — @Version detected stale read. Another thread already committed.",
                            threadName);
                    return "CONFLICT — rejected by @Version (stale data detected)";
                }
                cause = cause.getCause();
            }
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (TimeoutException e) {
            throw new RuntimeException("Thread timed out", e);
        }
    }
}
