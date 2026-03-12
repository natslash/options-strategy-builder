package com.natslash.options_strategy_builder.controller;

import com.natslash.options_strategy_builder.service.PaperTradingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/experiment")
@RequiredArgsConstructor
public class PaperTradingController {

    private final PaperTradingService paperTradingService;

    /**
     * Seeds a test user with €10,000 balance.
     * Run this before each experiment to start with a clean slate.
     *
     * curl -X POST http://localhost:8080/api/experiment/setup
     */
    @PostMapping("/setup")
    public ResponseEntity<Map<String, Object>> setup() {
        return ResponseEntity.ok(paperTradingService.setupTestUser());
    }

    /**
     * EXPERIMENT 1: Lost Update (BROKEN)
     * Two threads place orders simultaneously without transaction protection.
     * Watch the logs — one deduction gets silently lost.
     *
     * curl -X POST http://localhost:8080/api/experiment/lost-update
     */
    @PostMapping("/lost-update")
    public ResponseEntity<Map<String, Object>> lostUpdate() throws Exception {
        return ResponseEntity.ok(paperTradingService.runLostUpdateBroken());
    }

    /**
     * EXPERIMENT 2: Lost Update (FIXED)
     * Same scenario, but with @Transactional + @Version.
     * One thread succeeds, the other is rejected with OptimisticLockException.
     *
     * curl -X POST http://localhost:8080/api/experiment/lost-update-fixed
     */
    @PostMapping("/lost-update-fixed")
    public ResponseEntity<Map<String, Object>> lostUpdateFixed() throws Exception {
        return ResponseEntity.ok(paperTradingService.runLostUpdateFixed());
    }

    /**
     * Check current balance after any experiment.
     *
     * curl http://localhost:8080/api/experiment/balance
     */
    @GetMapping("/balance")
    public ResponseEntity<Map<String, Object>> balance() {
        return ResponseEntity.ok(paperTradingService.getBalance());
    }
}
