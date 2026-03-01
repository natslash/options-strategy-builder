package com.natslash.options_strategy_builder.service;

import com.natslash.options_strategy_builder.entity.Instrument;
import com.natslash.options_strategy_builder.model.ChainParams;
import com.natslash.options_strategy_builder.model.TickData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OptionsChainServiceTest {

    @Mock IbkrClientService         ibkr;
    @Mock RateLimitedRequestManager rateLimiter;
    @Mock TradingSchedule           schedule;

    @InjectMocks OptionsChainService service;

    Instrument instrument;
    ChainParams params;

    @BeforeEach
    void setUp() {
        instrument = new Instrument();
        instrument.setSymbol("OESX");
        instrument.setExchange("EUREX");
        instrument.setCurrency("EUR");
        instrument.setConId(12345);
        instrument.setMultiplier(100);
        instrument.setTradingClass("OESX");

        params = new ChainParams(
                List.of("20260320", "20260417"),
                List.of(5000.0, 5500.0, 6000.0, 6500.0, 7000.0));
    }

    // ── resolveSpot priority ───────────────────────────────────────────────

    /**
     * Reveals the production bug: IBKR index returns stale close (last=null, close=4360)
     * while the frontend already sent the correct live spot (6124.85).
     * After the fix, providedSpot must take priority over any IBKR value.
     */
    @Test
    void resolveSpot_returnsProvidedSpot_notStaleIbkrClose() throws Exception {
        // lenient: providedSpot > 0 short-circuits before any IBKR call
        lenient().when(ibkr.reqMktData(any(), anyInt()))
                .thenReturn(CompletableFuture.completedFuture(tickWith(null, 4360.0)));

        double result = service.resolveSpot(instrument, params, 6124.85);

        assertThat(result).isEqualTo(6124.85);
    }

    /**
     * When no spot is provided, live IBKR underlying price is fetched and used.
     * The underlying contract uses the 2-param reqMktData (snapshot=true).
     */
    @Test
    void resolveSpot_fetchesLiveIbkrSpot_whenNoProvidedSpot() throws Exception {
        // 2-param: underlying contract (snapshot=true, fast frozen close)
        when(ibkr.reqMktData(any(), anyInt()))
                .thenReturn(CompletableFuture.completedFuture(tickWith(6124.85, null)));

        double result = service.resolveSpot(instrument, params, null);

        assertThat(result).isEqualTo(6124.85);
    }

    /**
     * When IBKR has no price (off-hours, no subscription), falls back to median strike.
     * params has 5 strikes: [5000, 5500, 6000, 6500, 7000] → median = index 2 = 6000.
     * Chain fetch never fails due to missing spot data.
     */
    @Test
    void resolveSpot_usesMedianStrike_whenIbkrUnavailable() {
        when(ibkr.reqMktData(any(), anyInt()))
                .thenReturn(CompletableFuture.completedFuture(emptyTick()));

        double result = service.resolveSpot(instrument, params, null);

        assertThat(result).isEqualTo(6000.0); // median of [5000,5500,6000,6500,7000]
    }

    /**
     * RuntimeException is only thrown when strikes are empty — means there's genuinely
     * nothing to show (no chain data at all), not just missing market data.
     */
    @Test
    void resolveSpot_throws_whenNoStrikesAvailable() {
        when(ibkr.reqMktData(any(), anyInt()))
                .thenReturn(CompletableFuture.completedFuture(emptyTick()));
        ChainParams emptyParams = new ChainParams(List.of("20260320"), List.of());

        assertThatThrownBy(() -> service.resolveSpot(instrument, emptyParams, null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("No strikes available");
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private TickData tickWith(Double last, Double close) {
        return new TickData(null, null, last, close,
                null, null, null, null, null, null, null,
                0, 0, 0, 0, false);
    }

    private TickData emptyTick() {
        return new TickData(null, null, null, null,
                null, null, null, null, null, null, null,
                0, 0, 0, 0, false);
    }
}
