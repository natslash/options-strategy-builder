package com.natslash.options_strategy_builder.repository;

import com.natslash.options_strategy_builder.entity.ChainContract;
import com.natslash.options_strategy_builder.entity.ChainSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ChainContractRepository extends JpaRepository<ChainContract, Long> {
    List<ChainContract> findBySnapshot(ChainSnapshot snapshot);
}