package com.natslash.options_strategy_builder.repository;

import com.natslash.options_strategy_builder.entity.Instrument;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface InstrumentRepository extends JpaRepository<Instrument, Long> {
    List<Instrument> findByActiveTrue();
    Optional<Instrument> findBySymbolAndExchange(String symbol, String exchange);
}