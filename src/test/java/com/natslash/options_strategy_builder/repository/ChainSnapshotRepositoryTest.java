package com.natslash.options_strategy_builder.repository;

import com.natslash.options_strategy_builder.entity.ChainContract;
import com.natslash.options_strategy_builder.entity.ChainSnapshot;
import com.natslash.options_strategy_builder.entity.Instrument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

@DataJpaTest
class ChainSnapshotRepositoryTest {

    @Autowired ChainSnapshotRepository  snapshotRepo;
    @Autowired ChainContractRepository  contractRepo;
    @Autowired InstrumentRepository     instrumentRepo;

    Instrument instrument;

    @BeforeEach
    void setUp() {
        instrument = new Instrument();
        instrument.setSymbol("ESTX50");
        instrument.setExchange("EUREX");
        instrument.setName("EURO STOXX 50");
        instrument.setCurrency("EUR");
        instrument.setConId(4356500);
        instrument.setMultiplier(10);
        instrument.setTradingClass("OESX");
        instrument.setStrikeRange(10);
        instrument.setMaxExpiries(3);
        instrumentRepo.save(instrument);
    }

    // ── findTopByInstrumentOrderByFetchedAtDesc ───────────────────────────

    @Test
    void findTop_returnsLatestSnapshot_whenMultipleExist() {
        ChainSnapshot older  = snapshot(LocalDateTime.now().minusHours(2), 4900.0);
        ChainSnapshot latest = snapshot(LocalDateTime.now(),               5000.0);
        snapshotRepo.saveAll(List.of(older, latest));

        Optional<ChainSnapshot> result =
                snapshotRepo.findTopByInstrumentOrderByFetchedAtDesc(instrument);

        assertThat(result).isPresent();
        assertThat(result.get().getSpot()).isEqualTo(5000.0);
    }

    @Test
    void findTop_returnsEmpty_whenNoSnapshotExists() {
        Optional<ChainSnapshot> result =
                snapshotRepo.findTopByInstrumentOrderByFetchedAtDesc(instrument);

        assertThat(result).isEmpty();
    }

    @Test
    void findTop_ignoresSnapshotsForOtherInstruments() {
        Instrument other = new Instrument();
        other.setSymbol("DAX");
        other.setExchange("EUREX");
        other.setName("DAX");
        other.setCurrency("EUR");
        other.setConId(999);
        other.setMultiplier(25);
        other.setTradingClass("ODAX");
        other.setStrikeRange(10);
        other.setMaxExpiries(3);
        instrumentRepo.save(other);

        // Only snapshot is for `other`, not for `instrument`
        ChainSnapshot s = new ChainSnapshot();
        s.setInstrument(other);
        s.setFetchedAt(LocalDateTime.now());
        s.setSpot(18000.0);
        snapshotRepo.save(s);

        assertThat(snapshotRepo.findTopByInstrumentOrderByFetchedAtDesc(instrument)).isEmpty();
    }

    // ── ChainContractRepository.findBySnapshot ────────────────────────────

    @Test
    void findBySnapshot_returnsAllContractsForSnapshot() {
        ChainSnapshot snap = snapshot(LocalDateTime.now(), 5000.0);
        snapshotRepo.save(snap);

        ChainContract c1 = contract(snap, "20250320", 5000, "C");
        ChainContract c2 = contract(snap, "20250320", 5000, "P");
        contractRepo.saveAll(List.of(c1, c2));

        List<ChainContract> result = contractRepo.findBySnapshot(snap);

        assertThat(result).hasSize(2);
        assertThat(result).extracting("type").containsExactlyInAnyOrder("C", "P");
    }

    @Test
    void findBySnapshot_returnsEmpty_forSnapshotWithNoContracts() {
        ChainSnapshot snap = snapshot(LocalDateTime.now(), 5000.0);
        snapshotRepo.save(snap);

        assertThat(contractRepo.findBySnapshot(snap)).isEmpty();
    }

    @Test
    void findBySnapshot_doesNotReturnContractsFromOtherSnapshots() {
        ChainSnapshot snap1 = snapshot(LocalDateTime.now().minusMinutes(10), 4950.0);
        ChainSnapshot snap2 = snapshot(LocalDateTime.now(),                   5000.0);
        snapshotRepo.saveAll(List.of(snap1, snap2));

        contractRepo.save(contract(snap1, "20250320", 4800, "P"));
        contractRepo.save(contract(snap2, "20250320", 5000, "C"));

        List<ChainContract> forSnap2 = contractRepo.findBySnapshot(snap2);

        assertThat(forSnap2).hasSize(1);
        assertThat(forSnap2.get(0).getStrike()).isEqualTo(5000.0);
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private ChainSnapshot snapshot(LocalDateTime fetchedAt, double spot) {
        ChainSnapshot s = new ChainSnapshot();
        s.setInstrument(instrument);
        s.setFetchedAt(fetchedAt);
        s.setSpot(spot);
        s.setMarketHours(false);
        return s;
    }

    private ChainContract contract(ChainSnapshot snap, String expiry,
                                    double strike, String type) {
        ChainContract c = new ChainContract();
        c.setSnapshot(snap);
        c.setExpiry(expiry);
        c.setStrike(strike);
        c.setType(type);
        c.setDte(30);
        c.setGreeksSource("NONE");
        return c;
    }
}
