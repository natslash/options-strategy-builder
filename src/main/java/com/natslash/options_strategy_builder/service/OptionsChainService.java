package com.natslash.options_strategy_builder.service;

import com.ib.client.Contract;
import com.ib.client.Types;
import com.natslash.options_strategy_builder.entity.ChainContract;
import com.natslash.options_strategy_builder.entity.ChainSnapshot;
import com.natslash.options_strategy_builder.entity.Instrument;
import com.natslash.options_strategy_builder.model.ChainParams;
import com.natslash.options_strategy_builder.model.OptionContract;
import com.natslash.options_strategy_builder.model.TickData;
import com.natslash.options_strategy_builder.repository.ChainContractRepository;
import com.natslash.options_strategy_builder.repository.ChainSnapshotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class OptionsChainService {

    private static final int    WINDOW_MS         = 3000;
    private static final int    CACHE_TTL_MINUTES = 5;
    private static final int    MAX_DTE_DAYS      = 180;
    private static final DateTimeFormatter FMT    = DateTimeFormatter.ofPattern("yyyyMMdd");

    // IBKR market data types
    private static final int MDT_LIVE   = 1;
    private static final int MDT_FROZEN = 2;

    private final IbkrClientService         ibkr;
    private final RateLimitedRequestManager rateLimiter;
    private final ChainSnapshotRepository   snapshotRepo;
    private final ChainContractRepository   contractRepo;
    private final TradingSchedule           schedule;
    private final ChainContractMapper       mapper;

    // ═══════════════════════════════════════════════════════════
    // Public API
    // ═══════════════════════════════════════════════════════════

    public List<OptionContract> fetchChain(Instrument instrument, Double providedSpot, boolean forceRefresh)
            throws Exception {

        // 1. Check cache
        if (!forceRefresh) {
            Optional<ChainSnapshot> cached = snapshotRepo.findTopByInstrumentOrderByFetchedAtDesc(instrument);
            if (cached.isPresent() && isCacheValid(cached.get())) {
                log.info("Serving chain from cache (snapshot id={} fetchedAt={})",
                        cached.get().getId(), cached.get().getFetchedAt());
                return mapper.toOptionContracts(contractRepo.findBySnapshot(cached.get()), cached.get().getSpot());
            }
        }

        // 2. Set market data type — frozen outside hours so we get last known prices
        boolean marketHours = schedule.isMarketHours();
        if (!marketHours) {
            ibkr.reqMarketDataType(MDT_FROZEN);
            log.info("Outside market hours — using frozen data");
        } else {
            ibkr.reqMarketDataType(MDT_LIVE);
        }

        // 3. Get chain params
        ChainParams params =
                ibkr.reqChainParams(instrument.getSymbol(), "IND", instrument.getConId()).join();

        // 4. Fetch spot — try live/frozen option data first, fall back to DB, then providedSpot
        double spot;
        try {
            spot = fetchSpotFromOption(instrument, params);
            log.info("Spot for {} from IBKR: {}", instrument.getSymbol(), spot);
        } catch (RuntimeException e) {
            Optional<ChainSnapshot> lastSnapshot = snapshotRepo.findTopByInstrumentOrderByFetchedAtDesc(instrument);
            if (lastSnapshot.isPresent()) {
                spot = lastSnapshot.get().getSpot();
                log.warn("Could not fetch spot from IBKR — using last known spot from DB: {}", spot);
            } else if (providedSpot != null && providedSpot > 0) {
                spot = providedSpot;
                log.warn("Could not fetch spot from IBKR — using provided spot: {}", spot);
            } else {
                throw new RuntimeException("No spot price available for " + instrument.getSymbol()
                        + ". Please provide spot as a parameter or try during market hours.");
            }
        }

        // 5. Fetch full chain
        List<OptionContract> contracts = fetchFromIbkr(instrument, params, spot);

        // 6. Reset to live data
        ibkr.reqMarketDataType(MDT_LIVE);

        // 7. Persist
        persist(instrument, spot, contracts);

        return contracts;
    }

    // ═══════════════════════════════════════════════════════════
    // Cache
    // ═══════════════════════════════════════════════════════════

    private boolean isCacheValid(ChainSnapshot snapshot) {
        if (schedule.isMarketHours()) {
            return snapshot.getFetchedAt().isAfter(LocalDateTime.now().minusMinutes(CACHE_TTL_MINUTES));
        }
        return true;
    }

    // ═══════════════════════════════════════════════════════════
    // Spot price — from ATM option undPrice (no extra subscription needed)
    // ═══════════════════════════════════════════════════════════

    private double fetchSpotFromOption(Instrument instrument, ChainParams params)
            throws InterruptedException {

        // Pick a round-number strike as proxy ATM
        double proxyAtm = params.strikes().stream()
                .min(Comparator.comparingDouble(s -> s % 100))
                .orElseThrow(() -> new RuntimeException("No strikes available"));

        List<String> expiries = schedule.filterMonthlyExpiries(params.expirations(), MAX_DTE_DAYS);
        if (expiries.isEmpty())
            throw new RuntimeException("No expiries available for " + instrument.getSymbol());

        // Single call — undPrice comes free with tickOptionComputation
        ContractRequest req  = new ContractRequest(expiries.get(0), proxyAtm, "C");
        Contract        c    = buildOptionContract(req, instrument);
        TickData        tick = ibkr.reqMktData(c, WINDOW_MS).join();

        if (tick != null && tick.undPrice() != null && tick.undPrice() > 0) {
            log.info("Spot from undPrice of {}/{}/C: {}", expiries.get(0), proxyAtm, tick.undPrice());
            return tick.undPrice();
        }
        if (tick != null && tick.close() != null && tick.close() > 0) {
            log.info("Spot from close of {}/{}/C: {}", expiries.get(0), proxyAtm, tick.close());
            return tick.close();
        }
        throw new RuntimeException("Could not determine spot for " + instrument.getSymbol()
                + " from option contract");
    }

    // ═══════════════════════════════════════════════════════════
    // IBKR fetch
    // ═══════════════════════════════════════════════════════════

    private List<OptionContract> fetchFromIbkr(Instrument instrument,
                                                ChainParams params,
                                                double spot) {
        long fetchStart = System.currentTimeMillis();

        // Filter expiries
        List<String> expiries = schedule.filterMonthlyExpiries(params.expirations(), MAX_DTE_DAYS);
        expiries = expiries.subList(0, Math.min(expiries.size(), instrument.getMaxExpiries()));
        log.info("Expiries ({}): {}", expiries.size(), expiries);

        // Filter strikes around ATM
        int    atm         = (int) (Math.round(spot / 25.0) * 25);
        double rangePoints = instrument.getStrikeRange() * 25.0;
        List<Double> strikes = params.strikes().stream()
                .filter(s -> Math.abs(s - atm) <= rangePoints)
                .sorted()
                .toList();
        log.info("Spot={} ATM={} strikeRange=±{} → {} strikes", spot, atm, instrument.getStrikeRange(), strikes.size());

        // Build contract request list
        List<ContractRequest> requests = new ArrayList<>();
        for (String expiry : expiries)
            for (double strike : strikes) {
                requests.add(new ContractRequest(expiry, strike, "C"));
                requests.add(new ContractRequest(expiry, strike, "P"));
            }

        int multiplier = instrument.getMultiplier();
        log.info("Fetching {} contracts via rate limiter ({} req/s)",
                requests.size(), 1000 / RateLimitedRequestManager.RATE_MS);

        // Submit all fetches through the rate limiter. submit() enqueues immediately
        // and returns a promise; the scheduler fires one reqMktData per RATE_MS.
        List<CompletableFuture<Optional<OptionContract>>> futures = requests.stream()
                .map(req -> rateLimiter.submit(() ->
                        ibkr.reqMktData(buildOptionContract(req, instrument), WINDOW_MS)
                                .thenApply(tick -> {
                                    log.debug("{}/{}/{} bid={} greeks={}",
                                            req.expiry(), req.strike(), req.type(),
                                            tick.bid(), tick.greeksReceived());
                                    if (tick.hasData())
                                        return Optional.of(toOptionContract(req, tick, spot, multiplier));
                                    return Optional.<OptionContract>empty();
                                })
                                .exceptionally(ex -> {
                                    log.debug("Tick fetch failed {}/{}/{}: {}",
                                            req.expiry(), req.strike(), req.type(), ex.getMessage());
                                    return Optional.empty();
                                })
                ))
                .collect(Collectors.toList());

        List<OptionContract> chain = futures.stream()
                .map(CompletableFuture::join)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toCollection(ArrayList::new));

        List<OptionContract> result = dedup(chain);
        log.info("Chain complete — {} contracts ({} after dedup) in {}ms",
                chain.size(), result.size(), System.currentTimeMillis() - fetchStart);
        return result;
    }

    private List<OptionContract> dedup(List<OptionContract> chain) {
        Map<String, OptionContract> seen = new LinkedHashMap<>();
        for (OptionContract c : chain) {
            String key = c.getExpiry() + "_" + c.getStrike() + "_" + c.getType();
            seen.merge(key, c, (existing, incoming) ->
                    "IBKR".equals(incoming.getGreeksSource()) && !"IBKR".equals(existing.getGreeksSource())
                            ? incoming : existing);
        }
        return new ArrayList<>(seen.values());
    }

    // ═══════════════════════════════════════════════════════════
    // Persistence
    // ═══════════════════════════════════════════════════════════

    private void persist(Instrument instrument, double spot, List<OptionContract> contracts) {
        ChainSnapshot snapshot = new ChainSnapshot();
        snapshot.setInstrument(instrument);
        snapshot.setFetchedAt(LocalDateTime.now());
        snapshot.setSpot(spot);
        snapshot.setMarketHours(schedule.isMarketHours());
        snapshotRepo.save(snapshot);

        List<ChainContract> entities = contracts.stream()
                .map(c -> mapper.toChainContract(c, snapshot))
                .toList();
        contractRepo.saveAll(entities);

        log.info("Persisted snapshot id={} with {} contracts", snapshot.getId(), entities.size());
    }

    // ═══════════════════════════════════════════════════════════
    // Mapping — converts raw IBKR tick + request into an OptionContract
    // ═══════════════════════════════════════════════════════════

    private OptionContract toOptionContract(ContractRequest req, TickData tick,
                                             double spot, int multiplier) {
        LocalDate expDate    = LocalDate.parse(req.expiry(), FMT);
        int       dte        = (int) (expDate.toEpochDay() - LocalDate.now().toEpochDay());
        Double    mid        = tick.mid();
        Double    premiumEur = mid != null ? mid * multiplier : null;
        Double    otmPct     = spot > 0 ? (req.strike() - spot) / spot * 100.0 : null;

        return OptionContract.builder()
                .expiry(req.expiry())
                .dte(dte)
                .strike(req.strike())
                .type(req.type())
                .bid(tick.bid())
                .ask(tick.ask())
                .last(tick.last())
                .close(tick.close())
                .iv(tick.impliedVol() != null ? tick.impliedVol() * 100.0 : null)
                .delta(tick.delta())
                .gamma(tick.gamma())
                .theta(tick.theta())
                .vega(tick.vega())
                .undPrice(tick.undPrice())
                .midPrice(mid)
                .premiumEur(premiumEur)
                .otmPct(otmPct != null ? Math.round(otmPct * 10.0) / 10.0 : null)
                .volume(tick.volume())
                .openInterest(tick.openInterest())
                .greeksSource(tick.greeksReceived() ? "IBKR" : "NONE")
                .build();
    }

    // ═══════════════════════════════════════════════════════════
    // Contract builder
    // ═══════════════════════════════════════════════════════════

    private Contract buildOptionContract(ContractRequest req, Instrument instrument) {
        Contract c = new Contract();
        c.symbol(instrument.getSymbol());
        c.secType("OPT");
        c.exchange(instrument.getExchange());
        c.currency(instrument.getCurrency());
        c.lastTradeDateOrContractMonth(req.expiry());
        c.strike(req.strike());
        c.right("C".equals(req.type()) ? Types.Right.Call : Types.Right.Put);
        c.multiplier(String.valueOf(instrument.getMultiplier()));
        c.tradingClass(instrument.getTradingClass());
        return c;
    }

    private record ContractRequest(String expiry, double strike, String type) {}
}
