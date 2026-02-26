package com.natslash.options_strategy_builder.controller;

import com.ib.client.ContractDetails;
import com.natslash.options_strategy_builder.entity.Instrument;
import com.natslash.options_strategy_builder.model.InstrumentSearchResult;
import com.natslash.options_strategy_builder.model.OptionContract;
import com.natslash.options_strategy_builder.repository.InstrumentRepository;
import com.natslash.options_strategy_builder.service.IbkrClientService;
import com.natslash.options_strategy_builder.service.OptionsChainService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ChainController {

    private final OptionsChainService chainService;
    private final IbkrClientService ibkr;
    private final InstrumentRepository instrumentRepository;

    // ── Instruments ────────────────────────────────────────────

    @GetMapping("/instruments")
    public List<Instrument> getInstruments() {
        return instrumentRepository.findByActiveTrue();
    }

    /**
     * Search IBKR for index contracts matching a symbol.
     * GET /api/instruments/search?symbol=DAX
     */
    @GetMapping("/instruments/search")
    public ResponseEntity<List<InstrumentSearchResult>> searchInstruments(
            @RequestParam String symbol) throws Exception {

        List<ContractDetails> results = ibkr.reqContractDetails(symbol.toUpperCase(), "IND");

        List<InstrumentSearchResult> response = results.stream()
                .map(cd -> InstrumentSearchResult.builder()
                        .symbol(cd.contract().symbol())
                        .name(cd.longName())
                        .exchange(cd.contract().exchange())
                        .currency(cd.contract().currency())
                        .conId(cd.contract().conid())
                        .multiplier(parseMultiplier(cd.contract().multiplier()))
                        .tradingClass(cd.contract().tradingClass())
                        .alreadySaved(instrumentRepository
                                .findBySymbolAndExchange(cd.contract().symbol(), cd.contract().exchange())
                                .isPresent())
                        .build())
                .toList();

        return ResponseEntity.ok(response);
    }

    /**
     * Save a searched instrument to the DB.
     * POST /api/instruments
     */
    @PostMapping("/instruments")
    public ResponseEntity<Instrument> saveInstrument(@RequestBody InstrumentSearchResult req) {

        // Check if already exists
        var existing = instrumentRepository.findBySymbolAndExchange(req.getSymbol(), req.getExchange());
        if (existing.isPresent()) {
            existing.get().setActive(true);
            return ResponseEntity.ok(instrumentRepository.save(existing.get()));
        }

        Instrument inst = new Instrument();
        inst.setSymbol(req.getSymbol());
        inst.setName(req.getName());
        inst.setExchange(req.getExchange());
        inst.setCurrency(req.getCurrency());
        inst.setConId(req.getConId());
        inst.setMultiplier(req.getMultiplier());
        inst.setTradingClass(req.getTradingClass());
        inst.setStrikeRange(10); // sensible default — user can adjust later
        inst.setMaxExpiries(3);
        inst.setActive(true);

        return ResponseEntity.ok(instrumentRepository.save(inst));
    }

    // ── Chain ──────────────────────────────────────────────────
    @GetMapping("/chain")
    public ResponseEntity<List<OptionContract>> getChain(
            @RequestParam Long instrumentId,
            @RequestParam(required = false) Double spot,
            @RequestParam(defaultValue = "false") boolean forceRefresh) throws Exception {

        Instrument instrument = instrumentRepository.findById(instrumentId)
                .orElseThrow(() -> new IllegalArgumentException("Instrument not found: " + instrumentId));

        try {
            return ResponseEntity.ok(chainService.fetchChain(instrument, spot, forceRefresh));
        } catch (RuntimeException e) {
            log.warn("Chain fetch failed for {}: {}", instrument.getSymbol(), e.getMessage());
            return ResponseEntity.ok(List.of());
        }
    }

    // ── Helpers ────────────────────────────────────────────────

    private int parseMultiplier(String multiplier) {
        if (multiplier == null || multiplier.isBlank())
            return 1;
        try {
            return (int) Double.parseDouble(multiplier);
        } catch (NumberFormatException e) {
            return 1;
        }
    }
}
