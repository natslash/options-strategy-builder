package com.natslash.options_strategy_builder.controller;

import com.ib.client.ContractDetails;
import com.ib.client.Types;
import com.natslash.options_strategy_builder.entity.Instrument;
import com.natslash.options_strategy_builder.model.ChainAnalysisResult;
import com.natslash.options_strategy_builder.model.InstrumentSearchResult;
import com.natslash.options_strategy_builder.model.ChainFilterParams;
import com.natslash.options_strategy_builder.model.OptionContract;
import com.natslash.options_strategy_builder.model.IbkrHealthStatus;
import com.natslash.options_strategy_builder.repository.InstrumentRepository;
import com.natslash.options_strategy_builder.service.ChainAnalysisService;
import com.natslash.options_strategy_builder.service.IbkrClientService;
import com.natslash.options_strategy_builder.service.IbkrHealthCheckService;
import com.natslash.options_strategy_builder.service.OptionsChainService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ChainController {

    private final OptionsChainService chainService;
    private final ChainAnalysisService chainAnalysisService;
    private final IbkrClientService ibkr;
    private final InstrumentRepository instrumentRepository;
    private final IbkrHealthCheckService healthService;

    // ── Status ─────────────────────────────────────────────────

    @GetMapping("/status")
    public Map<String, Boolean> getStatus() {
        return Map.of("ibkrConnected", ibkr.isConnected());
    }

    // ── Instruments ────────────────────────────────────────────

    @GetMapping("/instruments")
    public List<Instrument> getInstruments() {
        return instrumentRepository.findByActiveTrue();
    }

    /**
     * Search IBKR for index and stock contracts matching a symbol.
     * Searches IND and STK in parallel, merges, deduplicates by conId.
     * GET /api/instruments/search?symbol=DAX
     */
    @GetMapping("/instruments/search")
    public ResponseEntity<List<InstrumentSearchResult>> searchInstruments(
            @RequestParam String symbol) {
        try {
            String sym = symbol.toUpperCase();
            // Run IND and STK searches in parallel — each may return 0 results without error
            CompletableFuture<List<ContractDetails>> indFut = ibkr.reqContractDetails(sym, "IND")
                    .exceptionally(e -> { log.debug("IND search for {} failed: {}", sym, e.getMessage()); return List.of(); });
            CompletableFuture<List<ContractDetails>> stkFut = ibkr.reqContractDetails(sym, "STK")
                    .exceptionally(e -> { log.debug("STK search for {} failed: {}", sym, e.getMessage()); return List.of(); });

            List<ContractDetails> ind = indFut.join();
            List<ContractDetails> stk = stkFut.join();
            log.info("Instrument search '{}': {} IND + {} STK results", sym, ind.size(), stk.size());

            // Dedup by conId — IND first, then STK; keeps insertion order
            LinkedHashMap<Integer, ContractDetails> deduped = new LinkedHashMap<>();
            for (ContractDetails cd : ind) if (cd.contract().conid() > 0) deduped.putIfAbsent(cd.contract().conid(), cd);
            for (ContractDetails cd : stk) if (cd.contract().conid() > 0) deduped.putIfAbsent(cd.contract().conid(), cd);

            // Only IND and STK have listed options — filter out anything else IBKR returns
            List<InstrumentSearchResult> response = deduped.values().stream()
                    .filter(cd -> cd.contract().secType() == Types.SecType.IND
                               || cd.contract().secType() == Types.SecType.STK)
                    .map(cd -> InstrumentSearchResult.builder()
                            .symbol(cd.contract().symbol())
                            .name(cd.longName())
                            .exchange(cd.contract().exchange())
                            .currency(cd.contract().currency())
                            .conId(cd.contract().conid())
                            .multiplier(parseMultiplier(cd.contract().multiplier()))
                            .tradingClass(cd.contract().tradingClass())
                            .secType(cd.contract().secType().name())   // enum → "IND" / "STK"
                            .alreadySaved(instrumentRepository
                                    .findBySymbolAndExchange(cd.contract().symbol(), cd.contract().exchange())
                                    .map(i -> Boolean.TRUE.equals(i.getActive()))
                                    .orElse(false))
                            .build())
                    .toList();

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.warn("Instrument search failed for {}: {}", symbol, e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
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
            Instrument inst = existing.get();
            inst.setActive(true);
            if (req.getSecType() != null) inst.setSecType(req.getSecType());
            return ResponseEntity.ok(instrumentRepository.save(inst));
        }

        Instrument inst = new Instrument();
        inst.setSymbol(req.getSymbol());
        inst.setName(req.getName());
        inst.setExchange(req.getExchange());
        inst.setCurrency(req.getCurrency());
        inst.setConId(req.getConId());
        inst.setMultiplier(req.getMultiplier());
        inst.setTradingClass(req.getTradingClass());
        inst.setSecType(req.getSecType());
        inst.setStrikeRange(10); // sensible default — user can adjust later
        inst.setMaxExpiries(3);
        inst.setActive(true);

        return ResponseEntity.ok(instrumentRepository.save(inst));
    }

    @DeleteMapping("/instruments/{id}")
    public ResponseEntity<Void> removeInstrument(@PathVariable Long id) {
        var opt = instrumentRepository.findById(id);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();
        Instrument inst = opt.get();
        inst.setActive(false);
        instrumentRepository.save(inst);
        return ResponseEntity.ok().build();
    }

    // ── Chain params (cheap: no tick data) ────────────────────
    @GetMapping("/chain/params")
    public ResponseEntity<ChainFilterParams> getChainParams(
            @RequestParam Long instrumentId) {

        Instrument instrument = instrumentRepository.findById(instrumentId)
                .orElseThrow(() -> new IllegalArgumentException("Instrument not found: " + instrumentId));

        try {
            return ResponseEntity.ok(chainService.fetchChainParams(instrument));
        } catch (IllegalArgumentException e) {
            // Instrument config problem (e.g. missing secType) — not a connection issue
            log.warn("Instrument config error for {}: {}", instrument.getSymbol(), e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            log.warn("Chain params unavailable for {}: {}", instrument.getSymbol(), e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        } catch (Exception e) {
            log.error("Failed to fetch chain params for {}", instrument.getSymbol(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    // ── Chain (full fetch with filter params) ─────────────────
    @GetMapping("/chain")
    public ResponseEntity<List<OptionContract>> getChain(
            @RequestParam Long instrumentId,
            @RequestParam(required = false) Double spot,
            @RequestParam(defaultValue = "false") boolean forceRefresh,
            @RequestParam(required = false) String expiry,
            @RequestParam(defaultValue = "true")   boolean includeMonthly,
            @RequestParam(defaultValue = "false")  boolean includeWeekly,
            @RequestParam(defaultValue = "ACTIVE") String  strikeFilter,
            @RequestParam(defaultValue = "25")     int     strikeCount) throws Exception { // must match OptionsChainService.DEFAULT_STRIKE_COUNT

        Instrument instrument = instrumentRepository.findById(instrumentId)
                .orElseThrow(() -> new IllegalArgumentException("Instrument not found: " + instrumentId));

        // Gate: cached health check (30s TTL) before expensive chain fetch
        IbkrHealthStatus health = healthService.getHealth();
        if (!health.healthy()) {
            log.warn("Chain request rejected — IBKR unhealthy: {}", health.detail());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }

        log.info("Chain request: instrumentId={} expiry={} spot={} strikeFilter={} strikeCount={} includeMonthly={} includeWeekly={}",
                instrumentId, expiry, spot, strikeFilter, strikeCount, includeMonthly, includeWeekly);

        try {
            return ResponseEntity.ok(chainService.fetchChain(
                    instrument, spot, forceRefresh, expiry,
                    includeMonthly, includeWeekly, strikeFilter, strikeCount));
        } catch (Exception e) {
            log.error("Chain fetch failed for {}: {}", instrument.getSymbol(), e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    // ── Chain analysis (max pain / PCR / OI walls) ────────────

    /**
     * POST /api/chain/{instrumentId}/analysis
     * Accepts a pre-fetched chain and returns max pain, PCR, and OI walls.
     * No IBKR call — pure calculation on provided chain data.
     */
    @PostMapping("/chain/{instrumentId}/analysis")
    public ResponseEntity<ChainAnalysisResult> analyzeChain(
            @PathVariable Long instrumentId,
            @RequestBody List<OptionContract> chain) {

        if (chain == null || chain.isEmpty())
            return ResponseEntity.badRequest().build();

        log.info("Chain analysis requested: instrumentId={} contracts={}", instrumentId, chain.size());
        ChainAnalysisResult result = chainAnalysisService.analyze(chain);
        return ResponseEntity.ok(result);
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
