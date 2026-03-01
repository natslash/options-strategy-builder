package com.natslash.options_strategy_builder.service;

import com.natslash.options_strategy_builder.model.StrategyLeg;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class LiquidityScorerTest {

    private final LiquidityScorer scorer = new LiquidityScorer();

    // ── Null / empty ───────────────────────────────────────────

    @Test
    void score_nullLegs_returnsNull() {
        assertThat(scorer.score(null)).isNull();
    }

    @Test
    void score_emptyLegs_returnsNull() {
        assertThat(scorer.score(List.of())).isNull();
    }

    @Test
    void score_noMarketData_returnsNull() {
        // Legs with no bid/ask/OI → nothing scoreable
        StrategyLeg leg = new StrategyLeg();
        assertThat(scorer.score(List.of(leg))).isNull();
    }

    // ── Spread penalty tiers ───────────────────────────────────

    @Test
    void score_tightSpread_spreadPenaltyIsOne() {
        // bid=99, ask=101 → spread=2, mid=100, spreadPct=2% → boundary: 2% < 2% is false so 0.5
        // Let's use bid=99.5, ask=100.5 → spread=1, mid=100, spreadPct=1% → 1.0
        StrategyLeg leg = legWithBidAsk(99.5, 100.5);
        // spreadPenalty=1.0, oiScore=0.0 (no OI), legScore=0.5
        assertThat(scorer.score(List.of(leg))).isCloseTo(0.5, within(0.01));
    }

    @Test
    void score_moderateSpread_spreadPenaltyIsHalf() {
        // bid=90, ask=110 → spread=20, mid=100, spreadPct=20% → >10% → penalty=0
        // bid=93, ask=107 → spread=14, mid=100, spreadPct=14% → >10% → 0.0
        // bid=95, ask=105 → spread=10, mid=100, spreadPct=10% → ≤10% → 0.5
        StrategyLeg leg = legWithBidAsk(95.0, 105.0);
        // spreadPenalty=0.5, oiScore=0.0, legScore=0.25
        assertThat(scorer.score(List.of(leg))).isCloseTo(0.25, within(0.01));
    }

    @Test
    void score_wideSpread_spreadPenaltyIsZero() {
        // bid=80, ask=120 → spread=40, mid=100, spreadPct=40% → 0.0
        StrategyLeg leg = legWithBidAsk(80.0, 120.0);
        // spreadPenalty=0.0, oiScore=0.0, legScore=0.0
        assertThat(scorer.score(List.of(leg))).isCloseTo(0.0, within(0.01));
    }

    // ── OI scoring ─────────────────────────────────────────────

    @Test
    void score_highOI_oiScoreIsOne() {
        // OI > 100 → oiScore=1.0
        StrategyLeg leg = legWithOI(200);
        // spreadPenalty=0.0 (no bid/ask), oiScore=1.0, legScore=0.5
        assertThat(scorer.score(List.of(leg))).isCloseTo(0.5, within(0.01));
    }

    @Test
    void score_mediumOI_oiScoreIsHalf() {
        // OI in (10, 100] → oiScore=0.5
        StrategyLeg leg = legWithOI(50);
        // legScore=0.25
        assertThat(scorer.score(List.of(leg))).isCloseTo(0.25, within(0.01));
    }

    @Test
    void score_lowOI_oiScoreIsZero() {
        StrategyLeg leg = legWithOI(5);
        // legScore=0.0
        assertThat(scorer.score(List.of(leg))).isCloseTo(0.0, within(0.01));
    }

    // ── Combined ───────────────────────────────────────────────

    @Test
    void score_tightSpreadHighOI_returnsOne() {
        // bid=99.5, ask=100.5, OI=500 → spreadPenalty=1.0, oiScore=1.0, legScore=1.0
        StrategyLeg leg = legWithBidAsk(99.5, 100.5);
        leg.setOpenInterest(500);
        assertThat(scorer.score(List.of(leg))).isCloseTo(1.0, within(0.01));
    }

    @Test
    void score_averagesAcrossLegs() {
        // Leg1: score=1.0 (tight spread + high OI), Leg2: score=0.0 (wide spread, no OI)
        StrategyLeg good = legWithBidAsk(99.5, 100.5);
        good.setOpenInterest(500);
        StrategyLeg bad = legWithBidAsk(80.0, 120.0);
        // avg = (1.0 + 0.0) / 2 = 0.5
        assertThat(scorer.score(List.of(good, bad))).isCloseTo(0.5, within(0.01));
    }

    @Test
    void score_legWithoutData_isExcludedFromAverage() {
        // Leg1: score=1.0, Leg2: no data → excluded → result = 1.0/1 = 1.0
        StrategyLeg good = legWithBidAsk(99.5, 100.5);
        good.setOpenInterest(500);
        StrategyLeg noData = new StrategyLeg();
        assertThat(scorer.score(List.of(good, noData))).isCloseTo(1.0, within(0.01));
    }

    // ── Helpers ───────────────────────────────────────────────

    private StrategyLeg legWithBidAsk(double bid, double ask) {
        StrategyLeg leg = new StrategyLeg();
        leg.setBid(bid);
        leg.setAsk(ask);
        return leg;
    }

    private StrategyLeg legWithOI(int oi) {
        StrategyLeg leg = new StrategyLeg();
        leg.setOpenInterest(oi);
        return leg;
    }
}
