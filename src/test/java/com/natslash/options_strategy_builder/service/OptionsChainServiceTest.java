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
     * IBKR is always the primary source. Even when providedSpot is supplied (from a prior
     * fetchChainParams call), the live IBKR price must win — providedSpot may be stale.
     */
    @Test
    void resolveSpot_alwaysUsesIbkrSpot_ignoresProvidedSpot() throws Exception {
        when(ibkr.reqUnderlyingPrice(any(), anyInt()))
                .thenReturn(CompletableFuture.completedFuture(tickWith(6200.0, null)));

        double result = service.resolveSpot(instrument, params, 6124.85); // providedSpot ignored

        assertThat(result).isEqualTo(6200.0); // IBKR live price wins
    }

    /**
     * No providedSpot — IBKR last price is fetched and returned directly.
     */
    @Test
    void resolveSpot_fetchesIbkrSpot_whenNoProvidedSpot() throws Exception {
        when(ibkr.reqUnderlyingPrice(any(), anyInt()))
                .thenReturn(CompletableFuture.completedFuture(tickWith(6124.85, null)));

        double result = service.resolveSpot(instrument, params, null);

        assertThat(result).isEqualTo(6124.85);
    }

    /**
     * IBKR returns no price (disconnected / no subscription) — providedSpot is accepted
     * as a fallback so the chain fetch can still proceed with the last known value.
     */
    @Test
    void resolveSpot_usesProvidedSpot_whenIbkrReturnsNoPrice() {
        when(ibkr.reqUnderlyingPrice(any(), anyInt()))
                .thenReturn(CompletableFuture.completedFuture(emptyTick()));

        double result = service.resolveSpot(instrument, params, 6000.0);

        assertThat(result).isEqualTo(6000.0);
    }

    /**
     * IBKR unavailable AND no providedSpot — must throw, not fall back to median strike.
     * Median-strike fallback produced inaccurate OTM% and silently misled the user.
     */
    @Test
    void resolveSpot_throws_whenIbkrUnavailableAndNoProvidedSpot() {
        when(ibkr.reqUnderlyingPrice(any(), anyInt()))
                .thenReturn(CompletableFuture.completedFuture(emptyTick()));

        assertThatThrownBy(() -> service.resolveSpot(instrument, params, null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("No spot price available");
    }

    // ── strikeByRange ──────────────────────────────────────────────────────

    /**
     * Spot=5000, ±10% → lo=4500, hi=5500. Strikes at 4500/5000/5500 are included (inclusive bounds).
     * Strikes at 4000 and 6000 are outside the range.
     */
    @Test
    void strikeByRange_returnsStrikesWithinPercentRange() {
        List<Double> strikes = List.of(4000.0, 4500.0, 5000.0, 5500.0, 6000.0);
        List<Double> result = OptionsChainService.strikeByRange(strikes, 5000.0, 10);
        assertThat(result).containsExactly(4500.0, 5000.0, 5500.0);
    }

    /**
     * The count emerges from the actual strike gap — no forced window size.
     * ESTX50-like: spot=5000, gap=25pts, ±10% → ~(1000/25)=40 strikes.
     */
    @Test
    void strikeByRange_countReflectsRealGap() {
        // Simulate 25-point ESTX50 grid from 4000 to 6000
        List<Double> strikes = new java.util.ArrayList<>();
        for (double s = 4000.0; s <= 6000.0; s += 25.0) strikes.add(s);
        List<Double> result = OptionsChainService.strikeByRange(strikes, 5000.0, 10);
        // ±10% of 5000 = 4500–5500, 25-point gap → 41 strikes
        assertThat(result.get(0)).isGreaterThanOrEqualTo(4500.0);
        assertThat(result.get(result.size() - 1)).isLessThanOrEqualTo(5500.0);
        assertThat(result.size()).isGreaterThan(30); // real gap produces real count, not a fixed 25
    }

    @Test
    void strikeByRange_spotBelowAllStrikes_returnsEmpty() {
        List<Double> strikes = List.of(5000.0, 5100.0, 5200.0);
        // spot=100 ±10% → lo=90, hi=110 — no strikes in that range
        List<Double> result = OptionsChainService.strikeByRange(strikes, 100.0, 10);
        assertThat(result).isEmpty();
    }

    @Test
    void strikeByRange_emptyList_returnsEmpty() {
        List<Double> result = OptionsChainService.strikeByRange(List.of(), 5000.0, 10);
        assertThat(result).isEmpty();
    }

    @Test
    void strikeByRange_zeroRange_returnsEmpty() {
        List<Double> result = OptionsChainService.strikeByRange(List.of(5000.0), 5000.0, 0);
        assertThat(result).isEmpty();
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
