package com.natslash.options_strategy_builder.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class TickDataTest {

    // ── mid() ─────────────────────────────────────────────────────────────

    @Test
    void mid_returnsMidpoint_whenBidAndAskPresent() {
        var tick = tickData(10.0, 12.0, null, null);
        assertThat(tick.mid()).isEqualTo(11.0);
    }

    @Test
    void mid_returnsBid_whenAskIsNull() {
        var tick = tickData(10.0, null, null, null);
        assertThat(tick.mid()).isEqualTo(10.0);
    }

    @Test
    void mid_returnsAsk_whenBidIsNull() {
        var tick = tickData(null, 12.0, null, null);
        assertThat(tick.mid()).isEqualTo(12.0);
    }

    @Test
    void mid_returnsLast_whenBidAndAskAreNull() {
        var tick = tickData(null, null, 9.5, null);
        assertThat(tick.mid()).isEqualTo(9.5);
    }

    @Test
    void mid_returnsNull_whenAllPricesNull() {
        var tick = tickData(null, null, null, null);
        assertThat(tick.mid()).isNull();
    }

    // ── hasData() ─────────────────────────────────────────────────────────

    @Test
    void hasData_true_whenBidPresent() {
        var tick = tickData(5.0, null, null, null);
        assertThat(tick.hasData()).isTrue();
    }

    @Test
    void hasData_true_whenAskPresent() {
        var tick = tickData(null, 5.0, null, null);
        assertThat(tick.hasData()).isTrue();
    }

    @Test
    void hasData_true_whenLastPresent() {
        var tick = tickData(null, null, 5.0, null);
        assertThat(tick.hasData()).isTrue();
    }

    @Test
    void hasData_true_whenClosePresent() {
        var tick = tickData(null, null, null, 5.0);
        assertThat(tick.hasData()).isTrue();
    }

    @Test
    void hasData_true_whenGreeksReceived() {
        var tick = new IbkrClientService.TickData(
                null, null, null, null,
                null, null,
                0.20, 0.5, 0.001, 0.05, -1.0,
                0, 0, true);
        assertThat(tick.hasData()).isTrue();
    }

    @Test
    void hasData_false_whenAllNullAndNoGreeks() {
        var tick = new IbkrClientService.TickData(
                null, null, null, null,
                null, null,
                null, null, null, null, null,
                0, 0, false);
        assertThat(tick.hasData()).isFalse();
    }

    // ── helper ────────────────────────────────────────────────────────────

    /** Creates a TickData with bid/ask/last/close set and all Greeks null. */
    private IbkrClientService.TickData tickData(Double bid, Double ask,
                                                  Double last, Double close) {
        return new IbkrClientService.TickData(
                bid, ask, last, close,
                null, null,
                null, null, null, null, null,
                0, 0, false);
    }
}
