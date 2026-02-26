package com.natslash.options_strategy_builder.service;

import com.ib.client.Contract;
import com.ib.client.Types;
import com.natslash.options_strategy_builder.entity.ChainContract;
import com.natslash.options_strategy_builder.entity.ChainSnapshot;
import com.natslash.options_strategy_builder.entity.Instrument;
import com.natslash.options_strategy_builder.model.OptionContract;
import com.natslash.options_strategy_builder.repository.ChainContractRepository;
import com.natslash.options_strategy_builder.repository.ChainSnapshotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class OptionsChainService {

    private static final int    BATCH_SIZE        = 40;
    private static final int    WINDOW_MS         = 3000;
    private static final int    BATCH_PAUSE_MS    = 100;
    private static final int    CACHE_TTL_MINUTES = 5;
    private static final int    MAX_DTE_DAYS      = 180;
    private static final DateTimeFormatter FMT    = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final ZoneId CET               = ZoneId.of("Europe/Berlin");

    // IBKR market data types
    private static final int MDT_LIVE    = 1;
    private static final int MDT_FROZEN  = 2;

    private final IbkrClientService       ibkr;
    private final ChainSnapshotRepository snapshotRepo;
    private final ChainContractRepository contractRepo;

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
                return toOptionContracts(contractRepo.findBySnapshot(cached.get()), cached.get().getSpot());
            }
        }

        // 2. Set market data type — frozen outside hours so we get last known prices
        boolean marketHours = isMarketHours();
        if (!marketHours) {
            ibkr.reqMarketDataType(MDT_FROZEN);
            log.info("Outside market hours — using frozen data");
        } else {
            ibkr.reqMarketDataType(MDT_LIVE);
        }

        // 3. Get chain params
        IbkrClientService.ChainParams params =
                ibkr.reqChainParams(instrument.getSymbol(), "IND", instrument.getConId());

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
        if (isMarketHours()) {
            return snapshot.getFetchedAt().isAfter(LocalDateTime.now().minusMinutes(CACHE_TTL_MINUTES));
        }
        return true;
    }

    private boolean isMarketHours() {
        ZonedDateTime now     = ZonedDateTime.now(CET);
        int           timeNow = now.getHour() * 100 + now.getMinute();
        return now.getDayOfWeek() != DayOfWeek.SATURDAY
                && now.getDayOfWeek() != DayOfWeek.SUNDAY
                && timeNow >= 900
                && timeNow <= 1730;
    }

    // ═══════════════════════════════════════════════════════════
    // Spot price — from ATM option undPrice (no extra subscription needed)
    // ═══════════════════════════════════════════════════════════

    private double fetchSpotFromOption(Instrument instrument, IbkrClientService.ChainParams params)
            throws InterruptedException {

        // Pick a round-number strike as proxy ATM
        double proxyAtm = params.strikes().stream()
                .min(Comparator.comparingDouble(s -> s % 100))
                .orElseThrow(() -> new RuntimeException("No strikes available"));

        List<String> expiries = filterMonthlyExpiries(params.expirations(), MAX_DTE_DAYS);
        if (expiries.isEmpty())
            throw new RuntimeException("No expiries available for " + instrument.getSymbol());

        // Single call — undPrice comes free with tickOptionComputation
        ContractRequest            req  = new ContractRequest(expiries.get(0), proxyAtm, "C");
        Contract                   c    = buildOptionContract(req, instrument);
        IbkrClientService.TickData tick = ibkr.reqMktData(c, WINDOW_MS);

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
                                                IbkrClientService.ChainParams params,
                                                double spot) throws Exception {
        long fetchStart = System.currentTimeMillis();

        // Filter expiries
        List<String> expiries = filterMonthlyExpiries(params.expirations(), MAX_DTE_DAYS);
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

        // Build requests
        List<ContractRequest> requests = new ArrayList<>();
        for (String expiry : expiries)
            for (double strike : strikes) {
                requests.add(new ContractRequest(expiry, strike, "C"));
                requests.add(new ContractRequest(expiry, strike, "P"));
            }
        log.info("Fetching {} contracts in batches of {}", requests.size(), BATCH_SIZE);

        // Fetch in batches using virtual threads
        List<OptionContract> chain    = new ArrayList<>();
        int                  batchNum = 0;

        for (int i = 0; i < requests.size(); i += BATCH_SIZE) {
            batchNum++;
            long                  batchStart = System.currentTimeMillis();
            List<ContractRequest> batch      = requests.subList(i, Math.min(i + BATCH_SIZE, requests.size()));
            List<Thread>          threads    = new ArrayList<>();
            List<OptionContract>  results    = Collections.synchronizedList(new ArrayList<>());

            for (ContractRequest req : batch) {
                Thread t = Thread.ofVirtual().start(() -> {
                    try {
                        long t0 = System.currentTimeMillis();
                        Contract c = buildOptionContract(req, instrument);
                        IbkrClientService.TickData tick = ibkr.reqMktData(c, WINDOW_MS);
                        log.debug("contract {}/{}/{} resolved in {}ms bid={} greeks={}",
                                req.expiry(), req.strike(), req.type(),
                                System.currentTimeMillis() - t0,
                                tick != null ? tick.bid() : null,
                                tick != null && tick.greeksReceived());
                        if (tick != null && tick.hasData())
                            results.add(toOptionContract(req, tick, spot, instrument.getMultiplier()));
                    } catch (Exception e) {
                        log.debug("Tick fetch failed {}/{}/{}: {}",
                                req.expiry(), req.strike(), req.type(), e.getMessage());
                    }
                });
                threads.add(t);
            }

            for (Thread t : threads) t.join();

            chain.addAll(results);
            log.info("Batch {}/{} ({} contracts) done in {}ms — {} results",
                    batchNum, (requests.size() + BATCH_SIZE - 1) / BATCH_SIZE,
                    batch.size(), System.currentTimeMillis() - batchStart, results.size());

            if (i + BATCH_SIZE < requests.size())
                Thread.sleep(BATCH_PAUSE_MS);
        }

        // Deduplicate
        Map<String, OptionContract> seen = new LinkedHashMap<>();
        for (OptionContract c : chain) {
            String key = c.getExpiry() + "_" + c.getStrike() + "_" + c.getType();
            seen.merge(key, c, (existing, incoming) ->
                    "IBKR".equals(incoming.getGreeksSource()) && !"IBKR".equals(existing.getGreeksSource())
                            ? incoming : existing);
        }

        List<OptionContract> result = new ArrayList<>(seen.values());
        log.info("Chain complete — {} contracts ({} after dedup) in {}ms",
                chain.size(), result.size(), System.currentTimeMillis() - fetchStart);
        return result;
    }

    // ═══════════════════════════════════════════════════════════
    // Persistence
    // ═══════════════════════════════════════════════════════════

    private void persist(Instrument instrument, double spot, List<OptionContract> contracts) {
        ChainSnapshot snapshot = new ChainSnapshot();
        snapshot.setInstrument(instrument);
        snapshot.setFetchedAt(LocalDateTime.now());
        snapshot.setSpot(spot);
        snapshot.setMarketHours(isMarketHours());
        snapshotRepo.save(snapshot);

        List<ChainContract> entities = contracts.stream()
                .map(c -> toChainContract(c, snapshot))
                .toList();
        contractRepo.saveAll(entities);

        log.info("Persisted snapshot id={} with {} contracts", snapshot.getId(), entities.size());
    }

    // ═══════════════════════════════════════════════════════════
    // Mapping
    // ═══════════════════════════════════════════════════════════

    private List<OptionContract> toOptionContracts(List<ChainContract> entities, double spot) {
        return entities.stream().map(e -> OptionContract.builder()
                .expiry(e.getExpiry())
                .dte(e.getDte())
                .strike(e.getStrike())
                .type(e.getType())
                .bid(e.getBid())
                .ask(e.getAsk())
                .midPrice(e.getMid())
                .close(e.getClose())
                .iv(e.getIv())
                .delta(e.getDelta())
                .gamma(e.getGamma())
                .theta(e.getTheta())
                .vega(e.getVega())
                .premiumEur(e.getPremiumEur())
                .otmPct(e.getOtmPct())
                .greeksSource(e.getGreeksSource())
                .build()).toList();
    }

    private ChainContract toChainContract(OptionContract oc, ChainSnapshot snapshot) {
        ChainContract e = new ChainContract();
        e.setSnapshot(snapshot);
        e.setExpiry(oc.getExpiry());
        e.setDte(oc.getDte());
        e.setStrike(oc.getStrike());
        e.setType(oc.getType());
        e.setBid(oc.getBid());
        e.setAsk(oc.getAsk());
        e.setMid(oc.getMidPrice());
        e.setClose(oc.getClose());
        e.setIv(oc.getIv());
        e.setDelta(oc.getDelta());
        e.setGamma(oc.getGamma());
        e.setTheta(oc.getTheta());
        e.setVega(oc.getVega());
        e.setPremiumEur(oc.getPremiumEur());
        e.setOtmPct(oc.getOtmPct());
        e.setGreeksSource(oc.getGreeksSource());
        return e;
    }

    private OptionContract toOptionContract(ContractRequest req, IbkrClientService.TickData tick,
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
    // Contract builders
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

    // ═══════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════

    private List<String> filterMonthlyExpiries(List<String> expirations, int maxDte) {
        LocalDate today  = LocalDate.now();
        LocalDate maxDay = today.plusDays(maxDte);
        return expirations.stream()
                .filter(e -> {
                    LocalDate d = LocalDate.parse(e, FMT);
                    return d.isAfter(today) && !d.isAfter(maxDay) && isThirdFriday(d);
                })
                .sorted()
                .toList();
    }

    private boolean isThirdFriday(LocalDate d) {
        return d.getDayOfWeek() == DayOfWeek.FRIDAY
                && d.getDayOfMonth() >= 15
                && d.getDayOfMonth() <= 21;
    }

    private record ContractRequest(String expiry, double strike, String type) {}
}
