package com.natslash.options_strategy_builder.service;

import com.ib.client.ContractDetails;
import com.natslash.options_strategy_builder.model.IbkrHealthStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Probe to verify IBGW connectivity before committing to an expensive chain fetch.
 *
 * Uses reqContractDetails("ESTX50", "IND") as the sentinel — always available
 * regardless of market hours or frozen mode. Also reports LIVE/FROZEN based on
 * TradingSchedule.isMarketHours() (Mon-Fri 09:00-17:30 CET; weekends = FROZEN).
 *
 * Results are cached for 30s to avoid probe overhead on every /api/chain request.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IbkrHealthCheckService {

    private static final int    PROBE_TIMEOUT_MS = 2_000;
    private static final long   CACHE_TTL_MS     = 30_000L;
    private static final String PROBE_SYMBOL     = "ESTX50";
    private static final String PROBE_SEC_TYPE   = "IND";

    private final IbkrClientService ibkr;
    private final TradingSchedule   tradingSchedule;

    private volatile IbkrHealthStatus cachedStatus;
    private volatile long             cacheExpiresAt;

    /** Cached check — used by /api/chain before heavy fetches. */
    public IbkrHealthStatus getHealth() {
        long now = System.currentTimeMillis();
        if (cachedStatus != null && now < cacheExpiresAt) return cachedStatus;
        IbkrHealthStatus fresh = probe();
        cachedStatus   = fresh;
        cacheExpiresAt = now + CACHE_TTL_MS;
        return fresh;
    }

    /** Always performs a live probe — used by GET /api/health/ibkr and tests (bypasses cache). */
    public IbkrHealthStatus probe() {
        Instant now      = Instant.now();
        String  dataMode = tradingSchedule.isMarketHours() ? "LIVE" : "FROZEN";

        if (!ibkr.isConnected())
            return IbkrHealthStatus.unhealthy(false, false, "IBGW socket not connected", dataMode, now);

        try {
            List<ContractDetails> results = ibkr.reqContractDetails(PROBE_SYMBOL, PROBE_SEC_TYPE)
                    .orTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .join();

            if (results.isEmpty()) {
                log.warn("Health probe: ESTX50 contract lookup returned no results");
                return IbkrHealthStatus.unhealthy(true, false,
                        "ESTX50 contract lookup returned no results — check IBKR connection", dataMode, now);
            }

            log.debug("Health probe OK: {} ESTX50 contracts returned, mode={}", results.size(), dataMode);
            return IbkrHealthStatus.healthy(dataMode, now);

        } catch (Exception e) {
            log.warn("Health probe failed: {}", e.getMessage());
            return IbkrHealthStatus.unhealthy(true, false,
                    "Health probe failed: " + e.getMessage(), dataMode, now);
        }
    }
}
