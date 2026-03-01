package com.natslash.options_strategy_builder.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class ProbabilityEngineTest {

    private final ProbabilityEngine engine = new ProbabilityEngine();

    // ── expectedMove ──────────────────────────────────────────

    @Test
    void expectedMove_standardInputs_matchesBlackScholesFormula() {
        // futuresPrice=5000, iv=0.20 (20%), dte=30
        // expected = 5000 * 0.20 * sqrt(30/365) ≈ 5000 * 0.20 * 0.2864 ≈ 286.4
        double em = engine.expectedMove(5000, 0.20, 30);
        assertThat(em).isCloseTo(286.4, within(1.0));
    }

    @Test
    void expectedMove_zeroDte_returnsZero() {
        double em = engine.expectedMove(5000, 0.20, 0);
        assertThat(em).isCloseTo(0.0, within(1e-9));
    }

    @Test
    void expectedMove_scalesLinearly_withPrice() {
        // Doubling the price should double the expected move
        double em1 = engine.expectedMove(5000, 0.20, 30);
        double em2 = engine.expectedMove(10000, 0.20, 30);
        assertThat(em2).isCloseTo(em1 * 2, within(0.01));
    }

    @Test
    void expectedMove_scalesLinearly_withIV() {
        // Doubling IV should double the expected move
        double em1 = engine.expectedMove(5000, 0.20, 30);
        double em2 = engine.expectedMove(5000, 0.40, 30);
        assertThat(em2).isCloseTo(em1 * 2, within(0.01));
    }

    // ── probabilityBetween ────────────────────────────────────

    @Test
    void probabilityBetween_symmetricRange_isApproximately68Pct() {
        // ±1 SD range → ~68.27% probability under a normal distribution
        double underlying = 5000;
        double em = engine.expectedMove(underlying, 0.20, 30);
        double p = engine.probabilityBetween(underlying - em, underlying + em, underlying, em);
        assertThat(p).isCloseTo(0.6827, within(0.001));
    }

    @Test
    void probabilityBetween_twoSdRange_isApproximately95Pct() {
        // ±2 SD range → ~95.45%
        double underlying = 5000;
        double em = engine.expectedMove(underlying, 0.20, 30);
        double p = engine.probabilityBetween(underlying - 2 * em, underlying + 2 * em, underlying, em);
        assertThat(p).isCloseTo(0.9545, within(0.001));
    }

    @Test
    void probabilityBetween_zeroWidth_returnsZero() {
        double p = engine.probabilityBetween(5000, 5000, 5000, 200);
        assertThat(p).isCloseTo(0.0, within(1e-6));
    }

    @Test
    void probabilityBetween_fullRange_returnsNearOne() {
        double p = engine.probabilityBetween(0, 1_000_000, 5000, 200);
        assertThat(p).isGreaterThan(0.9999);
    }

    @Test
    void probabilityBetween_zeroStdev_returnsZero() {
        // Guard: should not throw even with stdev=0
        double p = engine.probabilityBetween(4800, 5200, 5000, 0);
        assertThat(p).isCloseTo(0.0, within(1e-6));
    }

    @Test
    void probabilityBetween_boundsAreSymmetric() {
        // P(4800 to 5000) == P(5000 to 5200) for symmetric normal
        double underlying = 5000;
        double em = 200;
        double pLower = engine.probabilityBetween(4800, 5000, underlying, em);
        double pUpper = engine.probabilityBetween(5000, 5200, underlying, em);
        assertThat(pLower).isCloseTo(pUpper, within(1e-9));
    }
}
