package com.natslash.options_strategy_builder.service;

import com.natslash.options_strategy_builder.model.*;
import com.natslash.options_strategy_builder.repository.InstrumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class StrategyServiceTest {

    @Mock IbkrClientService ibkr;
    @Mock InstrumentRepository instrumentRepository;

    StrategyService service;

    @BeforeEach
    void setUp() {
        service = new StrategyService(ibkr, instrumentRepository);
    }

    // ── analyze() — Greeks ────────────────────────────────────────────────

    @Test
    void analyze_shortPut_netDeltaIsPositive() {
        // SHORT put: sign=-1, delta=-0.20 → netDelta = -1 * -0.20 = +0.20
        StrategyLeg leg = makeLeg("20250320", 4800, "P", "SHORT", 50.0);
        leg.setDelta(-0.20);
        leg.setGamma(0.001);
        leg.setTheta(5.0);
        leg.setVega(-0.5);

        StrategyAnalysis result = service.analyze(req("Short Put", 5000.0, leg));

        assertThat(result.getNetDelta()).isCloseTo(0.2, within(0.0001));
        assertThat(result.getNetGamma()).isCloseTo(-0.001, within(1e-6));
        assertThat(result.getNetTheta()).isCloseTo(-5.0, within(0.0001));
        assertThat(result.getNetVega()).isCloseTo(0.5, within(0.0001));
    }

    @Test
    void analyze_shortPut_netPremiumIsCreditWithDefaultMultiplier() {
        // multiplier = 10 (default, no instrumentId)
        // netPremium = -(-1) * 1 * 50 * 10 = +500 (credit)
        StrategyLeg leg = makeLeg("20250320", 4800, "P", "SHORT", 50.0);

        StrategyAnalysis result = service.analyze(req("Short Put", 5000.0, leg));

        assertThat(result.getNetPremium()).isCloseTo(500.0, within(0.01));
    }

    @Test
    void analyze_shortStraddle_isApproximatelyDeltaNeutral() {
        // ATM call delta +0.50 and put delta -0.50, both SHORT
        // netDelta = (-1)*0.50 + (-1)*(-0.50) = 0
        StrategyLeg call = makeLeg("20250320", 5000, "C", "SHORT", 100.0);
        call.setDelta(0.50);
        StrategyLeg put = makeLeg("20250320", 5000, "P", "SHORT", 100.0);
        put.setDelta(-0.50);

        StrategyAnalysis result = service.analyze(req("Short Straddle", 5000.0, call, put));

        assertThat(result.getNetDelta()).isCloseTo(0.0, within(0.0001));
        // credit = 2 * 100 * 10 = 2000
        assertThat(result.getNetPremium()).isCloseTo(2000.0, within(0.01));
    }

    @Test
    void analyze_longCall_hasPositiveDeltaAndDebitPremium() {
        // sign = +1, delta=0.3, premium=30
        // netDelta = 0.30, netPremium = -(+1)*1*30*10 = -300 (debit)
        StrategyLeg leg = makeLeg("20250320", 5200, "C", "LONG", 30.0);
        leg.setDelta(0.30);

        StrategyAnalysis result = service.analyze(req("Long Call", 5000.0, leg));

        assertThat(result.getNetDelta()).isCloseTo(0.30, within(0.0001));
        assertThat(result.getNetPremium()).isCloseTo(-300.0, within(0.01));
    }

    // ── analyze() — P&L at expiry ─────────────────────────────────────────

    @Test
    void analyze_shortPut_pnlBelowStrikeIsLoss() {
        // SHORT put@4800, premium=50, multiplier=10
        // At spot=4700: intrinsic=100, pnl = -1*(100-50)*10 = -500
        StrategyLeg leg = makeLeg("20250320", 4800, "P", "SHORT", 50.0);

        StrategyAnalysis result = service.analyze(req("Short Put", 5000.0, leg));

        assertThat(result.getPnlAtExpiry().get(4700)).isCloseTo(-500.0, within(0.01));
        assertThat(result.getPnlAtExpiry().get(4800)).isCloseTo(500.0, within(0.01));
        assertThat(result.getPnlAtExpiry().get(5000)).isCloseTo(500.0, within(0.01));
    }

    @Test
    void analyze_longCall_pnlAboveStrikeIsProfit() {
        // LONG call@5000, premium=0 for simplicity, multiplier=10
        // At spot=5100: intrinsic=100, pnl = +1*(100-0)*10 = 1000
        // At spot=4900: intrinsic=0, pnl = 0
        StrategyLeg leg = makeLeg("20250320", 5000, "C", "LONG", 0.0);

        StrategyAnalysis result = service.analyze(req("Long Call", 5000.0, leg));

        assertThat(result.getPnlAtExpiry().get(5100)).isCloseTo(1000.0, within(0.01));
        assertThat(result.getPnlAtExpiry().get(4900)).isCloseTo(0.0, within(0.01));
    }

    // ── analyze() — max profit / max loss ─────────────────────────────────

    @Test
    void analyze_shortPut_maxProfitIsPremiumCollected() {
        StrategyLeg leg = makeLeg("20250320", 4800, "P", "SHORT", 50.0);

        StrategyAnalysis result = service.analyze(req("Short Put", 5000.0, leg));

        assertThat(result.getMaxProfit()).isCloseTo(500.0, within(0.01));
    }

    @Test
    void analyze_longCall_maxLossIsLimitedToPremiumPaid() {
        StrategyLeg leg = makeLeg("20250320", 5200, "C", "LONG", 30.0);

        StrategyAnalysis result = service.analyze(req("Long Call", 5000.0, leg));

        assertThat(result.getMaxLoss()).isCloseTo(-300.0, within(0.01));
    }

    @Test
    void analyze_ironCondor_hasDefinedMaxProfitAndBoundedLoss() {
        // Short strangle 5100C/4900P + long wings 5200C/4800P
        // Premiums: shorts=20, longs=5
        // Net credit = (20+20-5-5)*10 = 300
        StrategyLeg longWingCall = makeLeg("20250320", 5200, "C", "LONG",  5.0);
        StrategyLeg shortOtmCall = makeLeg("20250320", 5100, "C", "SHORT", 20.0);
        StrategyLeg shortOtmPut  = makeLeg("20250320", 4900, "P", "SHORT", 20.0);
        StrategyLeg longWingPut  = makeLeg("20250320", 4800, "P", "LONG",  5.0);

        StrategyAnalysis result = service.analyze(
                new StrategyRequest("Iron Condor", 5000.0, null,
                        List.of(longWingCall, shortOtmCall, shortOtmPut, longWingPut)));

        assertThat(result.getNetPremium()).isCloseTo(300.0, within(0.01));
        assertThat(result.getMaxProfit()).isCloseTo(300.0, within(0.01));
        assertThat(result.getMaxLoss()).isLessThan(0);
        // Defined risk: loss is bounded (wing width - credit = 1000 - 300 = 700)
        assertThat(result.getMaxLoss()).isCloseTo(-700.0, within(0.01));
    }

    // ── analyze() — break-even ────────────────────────────────────────────

    @Test
    void analyze_shortPut_breakEvenAtStrikeMinusPremium() {
        // strike=4800, premium=40 → break-even = 4760 (between 4750 and 4775 grid steps)
        // Using a non-boundary premium avoids the double-zero-crossing edge case
        StrategyLeg leg = makeLeg("20250320", 4800, "P", "SHORT", 40.0);

        StrategyAnalysis result = service.analyze(req("Short Put", 5000.0, leg));

        assertThat(result.getBreakEvenLow()).isCloseTo(4760.0, within(1.0));
        assertThat(result.getBreakEvenHigh()).isNull();
    }

    @Test
    void analyze_shortStraddle_hasTwoSymmetricBreakEvens() {
        // ATM=5000, each premium=100, multiplier=10
        // Break-evens at 5000 ± 100 = 4900 ... wait:
        // total pnl = -10*(max(0,s-5000) + max(0,5000-s) - 200)
        // = 0 when max(0,s-5000)+max(0,5000-s) = 200
        // For s>5000: s-5000 = 200 → s=5200
        // For s<5000: 5000-s = 200 → s=4800
        StrategyLeg call = makeLeg("20250320", 5000, "C", "SHORT", 100.0);
        StrategyLeg put  = makeLeg("20250320", 5000, "P", "SHORT", 100.0);

        StrategyAnalysis result = service.analyze(req("Short Straddle", 5000.0, call, put));

        assertThat(result.getBreakEvenLow()).isCloseTo(4800.0, within(1.0));
        assertThat(result.getBreakEvenHigh()).isCloseTo(5200.0, within(1.0));
    }

    // ── buildPreset() — leg structure ─────────────────────────────────────

    @Test
    void buildPreset_shortPut_hasOnePutLeg() {
        PresetRequest req = preset("SHORT_PUT", 0.20);

        StrategyRequest result = service.buildPreset(req, 5000.0);

        assertThat(result.getLegs()).hasSize(1);
        assertThat(result.getLegs().get(0).getType()).isEqualTo("P");
        assertThat(result.getLegs().get(0).getDirection()).isEqualTo("SHORT");
    }

    @Test
    void buildPreset_shortStrangle_hasTwoShortLegs() {
        PresetRequest req = preset("SHORT_STRANGLE", 0.20);

        StrategyRequest result = service.buildPreset(req, 5000.0);

        assertThat(result.getLegs()).hasSize(2);
        assertThat(result.getLegs()).allMatch(l -> "SHORT".equals(l.getDirection()));
        assertThat(result.getLegs()).extracting("type").containsExactlyInAnyOrder("C", "P");
    }

    @Test
    void buildPreset_shortStraddle_callAndPutAtSameStrike() {
        PresetRequest req = preset("SHORT_STRADDLE", 0.20);

        StrategyRequest result = service.buildPreset(req, 5000.0);

        assertThat(result.getLegs()).hasSize(2);
        assertThat(result.getLegs().get(0).getStrike())
                .isEqualTo(result.getLegs().get(1).getStrike());
    }

    @Test
    void buildPreset_ironCondor_hasFourLegsInCorrectOrder() {
        PresetRequest req = preset("IRON_CONDOR", 0.20);

        StrategyRequest result = service.buildPreset(req, 5000.0);

        assertThat(result.getLegs()).hasSize(4);
        // Order: LONG C (wing), SHORT C, SHORT P, LONG P (wing)
        assertThat(result.getLegs()).extracting("direction")
                .containsExactly("LONG", "SHORT", "SHORT", "LONG");
        assertThat(result.getLegs()).extracting("type")
                .containsExactly("C", "C", "P", "P");
    }

    @Test
    void buildPreset_ironCondor_wingStrikesAreFartherFromAtmThanShorts() {
        PresetRequest req = preset("IRON_CONDOR", 0.20);

        StrategyRequest result = service.buildPreset(req, 5000.0);

        double longCallStrike  = result.getLegs().get(0).getStrike(); // wing call (higher)
        double shortCallStrike = result.getLegs().get(1).getStrike();
        double shortPutStrike  = result.getLegs().get(2).getStrike();
        double longPutStrike   = result.getLegs().get(3).getStrike();  // wing put (lower)

        assertThat(longCallStrike).isGreaterThan(shortCallStrike);
        assertThat(shortCallStrike).isGreaterThan(shortPutStrike);
        assertThat(shortPutStrike).isGreaterThan(longPutStrike);
    }

    @Test
    void buildPreset_ironButterfly_hasShortAtmAndLongWings() {
        PresetRequest req = preset("IRON_BUTTERFLY", 0.20);

        StrategyRequest result = service.buildPreset(req, 5000.0);

        assertThat(result.getLegs()).hasSize(4);
        // Both shorts should be at ATM
        double atm = result.getLegs().get(1).getStrike();
        assertThat(result.getLegs().get(2).getStrike()).isEqualTo(atm);
    }

    @Test
    void buildPreset_longStrangle_hasTwoLongLegs() {
        PresetRequest req = preset("LONG_STRANGLE", 0.20);

        StrategyRequest result = service.buildPreset(req, 5000.0);

        assertThat(result.getLegs()).hasSize(2);
        assertThat(result.getLegs()).allMatch(l -> "LONG".equals(l.getDirection()));
    }

    @Test
    void buildPreset_usesCustomName_whenProvided() {
        PresetRequest req = preset("SHORT_PUT", 0.20);
        req.setName("My Short Put");

        StrategyRequest result = service.buildPreset(req, 5000.0);

        assertThat(result.getName()).isEqualTo("My Short Put");
    }

    @Test
    void buildPreset_usesDisplayName_whenNameNotProvided() {
        PresetRequest req = preset("IRON_CONDOR", 0.20);

        StrategyRequest result = service.buildPreset(req, 5000.0);

        assertThat(result.getName()).isEqualTo("Iron Condor");
    }

    @Test
    void buildPreset_allQuantitiesAreOne() {
        PresetRequest req = preset("IRON_CONDOR", 0.20);

        StrategyRequest result = service.buildPreset(req, 5000.0);

        assertThat(result.getLegs()).allMatch(l -> l.getQuantity() == 1);
    }

    @Test
    void buildPreset_atmStrikeIsRoundedToNearest25() {
        // spot=5012 → atm = round(5012/25)*25 = round(200.48)*25 = 200*25 = 5000
        PresetRequest req = preset("SHORT_STRADDLE", 0.20);

        StrategyRequest result = service.buildPreset(req, 5012.0);

        // Both straddle legs should be at ATM = 5000
        assertThat(result.getLegs().get(0).getStrike()).isEqualTo(5000.0);
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private StrategyLeg makeLeg(String expiry, double strike, String type,
                                 String direction, Double premium) {
        StrategyLeg leg = new StrategyLeg();
        leg.setExpiry(expiry);
        leg.setStrike(strike);
        leg.setType(type);
        leg.setDirection(direction);
        leg.setQuantity(1);
        leg.setPremium(premium);
        return leg;
    }

    private StrategyRequest req(String name, double spot, StrategyLeg... legs) {
        return new StrategyRequest(name, spot, null, List.of(legs));
    }

    private PresetRequest preset(String presetName, double deltaTarget) {
        PresetRequest req = new PresetRequest();
        req.setPreset(presetName);
        req.setExpiry("20250320");
        req.setDeltaTarget(deltaTarget);
        // instrumentId = null → no IBKR call, no leg enrichment
        return req;
    }
}
