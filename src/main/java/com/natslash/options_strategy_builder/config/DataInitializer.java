package com.natslash.options_strategy_builder.config;

import com.natslash.options_strategy_builder.entity.Instrument;
import com.natslash.options_strategy_builder.repository.InstrumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements ApplicationRunner {

    private final InstrumentRepository instrumentRepository;

    @Override
    public void run(ApplicationArguments args) {
        if (instrumentRepository.findBySymbolAndExchange("ESTX50", "EUREX").isEmpty()) {
            Instrument estx50 = new Instrument();
            estx50.setSymbol("ESTX50");
            estx50.setName("EURO STOXX 50");
            estx50.setExchange("EUREX");
            estx50.setCurrency("EUR");
            estx50.setConId(4356500);
            estx50.setMultiplier(10);
            estx50.setTradingClass("OESX");
            estx50.setStrikeRange(10);
            estx50.setMaxExpiries(3);
            instrumentRepository.save(estx50);
            log.info("Seeded instrument: EURO STOXX 50");
        }
    }
}