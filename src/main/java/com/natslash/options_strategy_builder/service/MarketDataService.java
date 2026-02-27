package com.natslash.options_strategy_builder.service;

import com.natslash.options_strategy_builder.entity.Instrument;
import com.natslash.options_strategy_builder.model.ChainParams;
import com.natslash.options_strategy_builder.repository.InstrumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * IBKR-agnostic market data access for domain services.
 * Domain services (e.g. StrategyService) depend on this, not on IbkrClientService.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketDataService {

    private final IbkrClientService    ibkr;
    private final InstrumentRepository instrumentRepository;

    /**
     * Returns available option strikes for the given instrument.
     * Returns an empty list if the instrument is not found or IBKR is unreachable.
     */
    public List<Double> getAvailableStrikes(Long instrumentId) {
        if (instrumentId == null)
            return Collections.emptyList();
        try {
            Instrument instrument = instrumentRepository.findById(instrumentId).orElse(null);
            if (instrument == null) {
                log.warn("Instrument not found id={}", instrumentId);
                return Collections.emptyList();
            }
            ChainParams params =
                    ibkr.reqChainParams(instrument.getSymbol(), "IND", instrument.getConId()).join();
            return params.strikes();
        } catch (Exception e) {
            log.warn("Could not fetch strikes for instrumentId={}: {}", instrumentId, e.getMessage());
            return Collections.emptyList();
        }
    }
}
