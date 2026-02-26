package com.natslash.options_strategy_builder.repository;

import com.natslash.options_strategy_builder.entity.ChainSnapshot;
import com.natslash.options_strategy_builder.entity.Instrument;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface ChainSnapshotRepository extends JpaRepository<ChainSnapshot, Long> {
    Optional<ChainSnapshot> findTopByInstrumentOrderByFetchedAtDesc(Instrument instrument);
}