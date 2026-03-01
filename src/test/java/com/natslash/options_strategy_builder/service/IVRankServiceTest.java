package com.natslash.options_strategy_builder.service;

import com.natslash.options_strategy_builder.entity.Instrument;
import com.natslash.options_strategy_builder.model.HistoricalBar;
import com.natslash.options_strategy_builder.model.IVRankResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IVRankServiceTest {

    @Mock
    IbkrClientService ibkr;

    IVRankService service;

    @BeforeEach
    void setUp() {
        service = new IVRankService(ibkr);
    }

    // ── Helpers ───────────────────────────────────────────────

    private Instrument instrument(long id) {
        Instrument inst = new Instrument();
        inst.setId(id);
        inst.setSymbol("ESTX50");
        inst.setExchange("EUREX");
        inst.setConId(12345);
        return inst;
    }

    private List<HistoricalBar> ivBars(double min, double max, double current) {
        List<HistoricalBar> bars = new ArrayList<>();
        bars.add(new HistoricalBar("20240301", min, min, min, min));
        bars.add(new HistoricalBar("20240601", max, max, max, max));
        bars.add(new HistoricalBar("20250228", current, current, current, current));
        return bars;
    }

    private List<HistoricalBar> hvBars(double hv) {
        List<HistoricalBar> bars = new ArrayList<>();
        for (int i = 0; i < 21; i++)
            bars.add(new HistoricalBar("2025020" + i, hv, hv, hv, hv));
        return bars;
    }

    // ── Rank formula ──────────────────────────────────────────

    @Test
    void getIVRank_currentAtMax_returnsRankOfOne() {
        // min=0.10, max=0.30, current=0.30 → rank = (0.30-0.10)/(0.30-0.10) = 1.0
        when(ibkr.reqHistoricalData(any(), eq("1 Y"), eq("1 day"), eq("OPTION_IMPLIED_VOLATILITY")))
                .thenReturn(CompletableFuture.completedFuture(ivBars(0.10, 0.30, 0.30)));
        when(ibkr.reqHistoricalData(any(), eq("1 M"), eq("1 day"), eq("HISTORICAL_VOLATILITY")))
                .thenReturn(CompletableFuture.completedFuture(hvBars(0.20)));

        IVRankResult result = service.getIVRank(instrument(1L));

        assertThat(result).isNotNull();
        assertThat(result.ivRank()).isCloseTo(1.0, within(0.001));
        assertThat(result.label()).isEqualTo("HIGH");
    }

    @Test
    void getIVRank_currentAtMin_returnsRankOfZero() {
        // min=0.10, max=0.30, current=0.10 → rank = 0.0
        when(ibkr.reqHistoricalData(any(), eq("1 Y"), eq("1 day"), eq("OPTION_IMPLIED_VOLATILITY")))
                .thenReturn(CompletableFuture.completedFuture(ivBars(0.10, 0.30, 0.10)));
        when(ibkr.reqHistoricalData(any(), eq("1 M"), eq("1 day"), eq("HISTORICAL_VOLATILITY")))
                .thenReturn(CompletableFuture.completedFuture(hvBars(0.15)));

        IVRankResult result = service.getIVRank(instrument(2L));

        assertThat(result.ivRank()).isCloseTo(0.0, within(0.001));
        assertThat(result.label()).isEqualTo("LOW");
    }

    @Test
    void getIVRank_midRange_returnsCorrectRankAndLabel() {
        // min=0.10, max=0.30, current=0.18 → rank=(0.18-0.10)/(0.30-0.10)=0.40 → "MODERATE"
        when(ibkr.reqHistoricalData(any(), eq("1 Y"), eq("1 day"), eq("OPTION_IMPLIED_VOLATILITY")))
                .thenReturn(CompletableFuture.completedFuture(ivBars(0.10, 0.30, 0.18)));
        when(ibkr.reqHistoricalData(any(), eq("1 M"), eq("1 day"), eq("HISTORICAL_VOLATILITY")))
                .thenReturn(CompletableFuture.completedFuture(hvBars(0.18)));

        IVRankResult result = service.getIVRank(instrument(3L));

        assertThat(result.ivRank()).isCloseTo(0.40, within(0.001));
        assertThat(result.label()).isEqualTo("MODERATE");
    }

    // ── Label boundaries ─────────────────────────────────────

    @Test
    void label_below25Pct_isLow() {
        assertThat(IVRankResult.labelFor(0.24)).isEqualTo("LOW");
    }

    @Test
    void label_at25Pct_isModerate() {
        assertThat(IVRankResult.labelFor(0.25)).isEqualTo("MODERATE");
    }

    @Test
    void label_at50Pct_isElevated() {
        assertThat(IVRankResult.labelFor(0.50)).isEqualTo("ELEVATED");
    }

    @Test
    void label_at75Pct_isHigh() {
        assertThat(IVRankResult.labelFor(0.75)).isEqualTo("HIGH");
    }

    // ── hvRatio ───────────────────────────────────────────────

    @Test
    void getIVRank_ivAboveHV_hvRatioGreaterThanOne() {
        // currentIv=0.25, hv30=0.20 → hvRatio=1.25 (selling edge)
        when(ibkr.reqHistoricalData(any(), eq("1 Y"), eq("1 day"), eq("OPTION_IMPLIED_VOLATILITY")))
                .thenReturn(CompletableFuture.completedFuture(ivBars(0.10, 0.30, 0.25)));
        when(ibkr.reqHistoricalData(any(), eq("1 M"), eq("1 day"), eq("HISTORICAL_VOLATILITY")))
                .thenReturn(CompletableFuture.completedFuture(hvBars(0.20)));

        IVRankResult result = service.getIVRank(instrument(4L));

        assertThat(result.hvRatio()).isCloseTo(1.25, within(0.01));
    }

    // ── IBKR unavailable ────────────────────────────────────

    @Test
    void getIVRank_ibkrUnavailable_returnsNull() {
        when(ibkr.reqHistoricalData(any(), any(), any(), any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("IBKR offline")));

        IVRankResult result = service.getIVRank(instrument(5L));

        assertThat(result).isNull();
    }

    @Test
    void getIVRank_emptyIVBars_returnsNull() {
        when(ibkr.reqHistoricalData(any(), eq("1 Y"), eq("1 day"), eq("OPTION_IMPLIED_VOLATILITY")))
                .thenReturn(CompletableFuture.completedFuture(List.of()));
        when(ibkr.reqHistoricalData(any(), eq("1 M"), eq("1 day"), eq("HISTORICAL_VOLATILITY")))
                .thenReturn(CompletableFuture.completedFuture(hvBars(0.20)));

        IVRankResult result = service.getIVRank(instrument(6L));

        assertThat(result).isNull();
    }

    // ── Cache ────────────────────────────────────────────────

    @Test
    void getIVRank_calledTwice_ibkrCalledOnceOnly() {
        when(ibkr.reqHistoricalData(any(), eq("1 Y"), eq("1 day"), eq("OPTION_IMPLIED_VOLATILITY")))
                .thenReturn(CompletableFuture.completedFuture(ivBars(0.10, 0.30, 0.20)));
        when(ibkr.reqHistoricalData(any(), eq("1 M"), eq("1 day"), eq("HISTORICAL_VOLATILITY")))
                .thenReturn(CompletableFuture.completedFuture(hvBars(0.20)));

        Instrument inst = instrument(7L);
        service.getIVRank(inst);
        service.getIVRank(inst); // second call — should hit cache

        // Each historical data call happens exactly once (IV + HV = 2 calls total)
        verify(ibkr, times(2)).reqHistoricalData(any(), any(), any(), any());
    }
}
