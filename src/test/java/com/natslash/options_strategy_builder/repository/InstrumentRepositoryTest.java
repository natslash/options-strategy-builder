package com.natslash.options_strategy_builder.repository;

import com.natslash.options_strategy_builder.entity.Instrument;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

@DataJpaTest
class InstrumentRepositoryTest {

    @Autowired InstrumentRepository repo;

    @Test
    void findByActiveTrue_returnsOnlyActiveInstruments() {
        Instrument active   = instrument("ESTX50", "EUREX", true);
        Instrument inactive = instrument("DAX",    "EUREX", false);
        repo.saveAll(List.of(active, inactive));

        List<Instrument> result = repo.findByActiveTrue();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getSymbol()).isEqualTo("ESTX50");
    }

    @Test
    void findByActiveTrue_returnsEmptyList_whenNoneActive() {
        repo.save(instrument("DAX", "EUREX", false));

        assertThat(repo.findByActiveTrue()).isEmpty();
    }

    @Test
    void findBySymbolAndExchange_returnsInstrument_whenExists() {
        repo.save(instrument("ESTX50", "EUREX", true));

        Optional<Instrument> result = repo.findBySymbolAndExchange("ESTX50", "EUREX");

        assertThat(result).isPresent();
        assertThat(result.get().getSymbol()).isEqualTo("ESTX50");
        assertThat(result.get().getExchange()).isEqualTo("EUREX");
    }

    @Test
    void findBySymbolAndExchange_returnsEmpty_whenNotFound() {
        repo.save(instrument("ESTX50", "EUREX", true));

        assertThat(repo.findBySymbolAndExchange("DAX", "EUREX")).isEmpty();
        assertThat(repo.findBySymbolAndExchange("ESTX50", "NYSE")).isEmpty();
    }

    @Test
    void findBySymbolAndExchange_doesNotMatchPartialSymbol() {
        repo.save(instrument("ESTX50", "EUREX", true));

        assertThat(repo.findBySymbolAndExchange("ESTX", "EUREX")).isEmpty();
    }

    // ── helper ────────────────────────────────────────────────────────────

    private Instrument instrument(String symbol, String exchange, boolean active) {
        Instrument inst = new Instrument();
        inst.setSymbol(symbol);
        inst.setExchange(exchange);
        inst.setActive(active);
        inst.setName(symbol + " index");
        inst.setCurrency("EUR");
        inst.setConId(12345);
        inst.setMultiplier(10);
        inst.setTradingClass(symbol);
        inst.setStrikeRange(10);
        inst.setMaxExpiries(3);
        return inst;
    }
}
