package com.natslash.options_strategy_builder.controller;

import com.natslash.options_strategy_builder.model.IbkrHealthStatus;
import com.natslash.options_strategy_builder.service.IbkrHealthCheckService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/health")
@RequiredArgsConstructor
public class HealthController {

    private final IbkrHealthCheckService healthService;

    /** Fresh probe every time — bypasses cache. Returns 200 if healthy, 503 if not. */
    @GetMapping("/ibkr")
    public ResponseEntity<IbkrHealthStatus> getIbkrHealth() {
        IbkrHealthStatus status = healthService.probe();
        return ResponseEntity.status(status.healthy() ? 200 : 503).body(status);
    }
}
