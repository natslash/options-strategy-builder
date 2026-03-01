package com.natslash.options_strategy_builder.service;

import com.natslash.options_strategy_builder.model.ChainAnalysisResult;
import com.natslash.options_strategy_builder.model.OptionContract;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class ChainAnalysisServiceTest {

    private final ChainAnalysisService service = new ChainAnalysisService();

    // ── Helpers ───────────────────────────────────────────────

    private OptionContract opt(double strike, String type, int oi) {
        return OptionContract.builder()
                .strike(strike)
                .type(type)
                .openInterest(oi)
                .expiry("20250320")
                .dte(30)
                .build();
    }

    // ── maxPain ───────────────────────────────────────────────

    @Test
    void maxPain_trivialChain_returnsStrikeWithMinimumPain() {
        // Single strike = only candidate — max pain must be that strike
        List<OptionContract> chain = List.of(
                opt(5000, "C", 100),
                opt(5000, "P", 100)
        );
        ChainAnalysisResult result = service.analyze(chain);
        assertThat(result.maxPain()).isEqualTo(5000.0);
    }

    @Test
    void maxPain_knownFixture_returnsCorrectStrike() {
        // Fixture: calls loaded at 5100, puts loaded at 4900
        // Max pain is the point minimising writer pain — at 5000 neither calls nor puts are ITM
        // Call writers: 5100C with OI=500 — ITM only if settlement > 5100
        // Put writers: 4900P with OI=500 — ITM only if settlement < 4900
        // At 5000: no option is ITM → total pain = 0 (minimum)
        List<OptionContract> chain = List.of(
                opt(4900, "P", 500),
                opt(5000, "C", 10),
                opt(5000, "P", 10),
                opt(5100, "C", 500)
        );
        double maxPain = service.analyze(chain).maxPain();
        // At 5000 both OTM options have zero intrinsic → pain = 0
        assertThat(maxPain).isEqualTo(5000.0);
    }

    @Test
    void maxPain_emptyChain_returnsZero() {
        ChainAnalysisResult result = service.analyze(List.of());
        assertThat(result.maxPain()).isEqualTo(0.0);
        assertThat(result.pcr()).isEqualTo(0.0);
        assertThat(result.oiWalls()).isEmpty();
    }

    // ── PCR ───────────────────────────────────────────────────

    @Test
    void pcr_equalPutAndCallOI_returnsOne() {
        List<OptionContract> chain = List.of(
                opt(5000, "C", 100),
                opt(5000, "P", 100)
        );
        assertThat(service.analyze(chain).pcr()).isCloseTo(1.0, within(0.001));
    }

    @Test
    void pcr_moreCallsThanhPuts_returnsLessThanOne() {
        List<OptionContract> chain = List.of(
                opt(5000, "C", 200),
                opt(5000, "P", 100)
        );
        assertThat(service.analyze(chain).pcr()).isCloseTo(0.5, within(0.001));
    }

    @Test
    void pcr_noCallOI_returnsZero() {
        List<OptionContract> chain = List.of(
                opt(5000, "P", 100)
        );
        assertThat(service.analyze(chain).pcr()).isEqualTo(0.0);
    }

    // ── OI walls ─────────────────────────────────────────────

    @Test
    void oiWalls_strikeWith3xAvgOI_isIncluded() {
        // Strikes: 4900 (OI=10), 5000 (OI=10), 5100 (OI=300)
        // avgOI = (10+10+300)/3 = 106.67 across all contracts (call+put combined at each strike)
        // Combined OI at 5100 = 300 (only 1 contract) ... let's set up properly
        // 3 strikes × 1 contract each: 4900C(OI=10), 5000C(OI=10), 5100C(OI=300)
        // avgOI = (10+10+300)/3 = 106.67
        // oiByStrike: 4900→10, 5000→10, 5100→300
        // 2×avgOI = 213.3 → only 5100 qualifies
        List<OptionContract> chain = List.of(
                opt(4900, "C", 10),
                opt(5000, "C", 10),
                opt(5100, "C", 300)
        );
        assertThat(service.analyze(chain).oiWalls()).containsExactly(5100.0);
    }

    @Test
    void oiWalls_noStrikeExceeds2xAvg_returnsEmpty() {
        List<OptionContract> chain = List.of(
                opt(4900, "C", 100),
                opt(5000, "C", 100),
                opt(5100, "C", 110)  // slightly above avg but not 2×
        );
        assertThat(service.analyze(chain).oiWalls()).isEmpty();
    }

    // ── PCR by expiry ─────────────────────────────────────────

    @Test
    void pcrByExpiry_twoExpiries_computedSeparately() {
        List<OptionContract> chain = List.of(
                OptionContract.builder().strike(5000).type("C").openInterest(100).expiry("20250320").dte(20).build(),
                OptionContract.builder().strike(5000).type("P").openInterest(200).expiry("20250320").dte(20).build(),
                OptionContract.builder().strike(5000).type("C").openInterest(50).expiry("20250417").dte(47).build(),
                OptionContract.builder().strike(5000).type("P").openInterest(50).expiry("20250417").dte(47).build()
        );
        Map<String, Double> byExpiry = service.analyze(chain).pcByExpiry();
        assertThat(byExpiry).containsKey("20250320");
        assertThat(byExpiry.get("20250320")).isCloseTo(2.0, within(0.001));  // 200/100
        assertThat(byExpiry.get("20250417")).isCloseTo(1.0, within(0.001));  // 50/50
    }
}
