package com.natslash.options_strategy_builder.service;

import com.ib.client.Contract;
import com.ib.client.Types;
import com.natslash.options_strategy_builder.entity.Instrument;
import com.natslash.options_strategy_builder.model.ChainFilterParams;
import com.natslash.options_strategy_builder.model.ChainParams;
import com.natslash.options_strategy_builder.model.OptionContract;
import com.natslash.options_strategy_builder.model.TickData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.DoubleSummaryStatistics;

@Slf4j
@Service
@RequiredArgsConstructor
public class OptionsChainService {

    private static final int    WINDOW_MS              = 1500;
    private static final int    MAX_DTE_DAYS           = 180;
    private static final long   PARAMS_CACHE_TTL_MS    = 60 * 60 * 1000L; // 1 hour
    private static final DateTimeFormatter FMT         = DateTimeFormatter.ofPattern("yyyyMMdd");

    // IBKR market data types
    private static final int MDT_LIVE   = 1;
    private static final int MDT_FROZEN = 2;

    private final IbkrClientService         ibkr;
    private final RateLimitedRequestManager rateLimiter;
    private final TradingSchedule           schedule;

    /** In-memory cache for ChainParams (expiries + strikes). Avoids redundant reqSecDefOptParams
     *  on every Fetch click — params change at most once a day. */
    private final Map<Long, CachedParams> paramsCache = new ConcurrentHashMap<>();

    private record CachedParams(ChainParams params, long expiresAt) {
        boolean isValid() { return System.currentTimeMillis() < expiresAt; }
    }

    // ═══════════════════════════════════════════════════════════
    // Public API
    // ═══════════════════════════════════════════════════════════

    // ═══════════════════════════════════════════════════════════
    // Params-only endpoint — cheap, no tick data
    // ═══════════════════════════════════════════════════════════

    public ChainFilterParams fetchChainParams(Instrument instrument) throws Exception {
        boolean marketHours = schedule.isMarketHours();
        ibkr.reqMarketDataType(marketHours ? MDT_LIVE : MDT_FROZEN);

        // Fire spot request concurrently with params fetch.
        // Use conId-based contract (not secType=IND) so this works for both index and
        // futures-based underlyings (e.g. DAX index vs ESTX50 futures).
        // snapshot=true: frozen close is immediately available; no need to stream for 1s.
        CompletableFuture<TickData> spotFut = ibkr.reqMktData(buildUnderlyingContract(instrument), WINDOW_MS);
        ChainParams params = getCachedParams(instrument);

        TickData spotTick = spotFut.join();
        List<Double> sortedStrikes = params.strikes().stream().sorted().toList();
        double spot = extractSpot(spotTick, instrument.getSymbol())
                .orElseGet(() -> {
                    if (sortedStrikes.isEmpty()) return 0.0;
                    double median = sortedStrikes.get(sortedStrikes.size() / 2);
                    log.warn("{}: no underlying price from IBKR — using median strike {} for display",
                            instrument.getSymbol(), median);
                    return median;
                });

        List<String> expiries = params.expirations().stream().sorted().toList();
        DoubleSummaryStatistics stats = params.strikes().stream()
                .mapToDouble(Double::doubleValue).summaryStatistics();
        log.info("ChainParams for {}: {} expiries, strikes {}-{}, spot={}",
                instrument.getSymbol(), expiries.size(), stats.getMin(), stats.getMax(), spot);
        return new ChainFilterParams(spot, expiries, stats.getMin(), stats.getMax(),
                (int) stats.getCount());
    }

    /**
     * Returns ChainParams for the instrument. Fallback order:
     * 1. Valid in-memory cache (fastest)
     * 2. IBKR fetch (updates cache)
     * 3. Stale in-memory cache (IBKR unavailable — weekends, server restart)
     */
    private ChainParams getCachedParams(Instrument instrument) throws Exception {
        // Validate before attempting IBKR fetch — must not enter the try-catch
        // or the outer catch will replace this message with "Connect to IBGW"
        String secType = instrument.getSecType();
        if (secType == null)
            throw new IllegalArgumentException(
                    instrument.getSymbol() + " has no instrument type stored — remove it and re-add via Search");

        CachedParams cached = paramsCache.get(instrument.getId());
        if (cached != null && cached.isValid()) {
            log.debug("Reusing cached params for {}", instrument.getSymbol());
            return cached.params();
        }
        try {
            ChainParams params = ibkr.reqChainParams(
                    instrument.getSymbol(), secType, instrument.getConId()).join();
            paramsCache.put(instrument.getId(),
                    new CachedParams(params, System.currentTimeMillis() + PARAMS_CACHE_TTL_MS));
            return params;
        } catch (Exception e) {
            if (cached != null) {
                log.warn("IBKR params unavailable for {} — serving stale cache: {}",
                        instrument.getSymbol(), e.getMessage());
                return cached.params();
            }
            throw new IllegalStateException(
                    "No chain params available for " + instrument.getSymbol()
                    + ". Connect to IBGW and fetch at least once during market hours.", e);
        }
    }

    public List<OptionContract> fetchChain(Instrument instrument, Double providedSpot,
                                            boolean forceRefresh, String expiry,
                                            boolean includeMonthly, boolean includeWeekly,
                                            String strikeFilter, double strikeRangePct)
            throws Exception {

        // 1. Set market data type — frozen outside hours so we get last known prices
        boolean marketHours = schedule.isMarketHours();
        if (!marketHours) {
            ibkr.reqMarketDataType(MDT_FROZEN);
            log.info("Outside market hours — using frozen data");
        } else {
            ibkr.reqMarketDataType(MDT_LIVE);
        }

        // 2. Get chain params — served from in-memory cache if still valid (avoids redundant IBKR call)
        ChainParams params = getCachedParams(instrument);

        // 3. Fetch spot — chain centering and OTM% both depend on current spot
        double spot = resolveSpot(instrument, params, providedSpot);

        // 4. Fetch from IBKR with configured filters
        // Frozen mode needs extra time for the model calculation to arrive
        int tickTimeout = marketHours ? WINDOW_MS : WINDOW_MS + 500;
        List<OptionContract> contracts = fetchFromIbkr(
                instrument, params, spot, expiry, includeMonthly, includeWeekly, strikeFilter, strikeRangePct, tickTimeout, marketHours);

        // 5. Reset to live data
        ibkr.reqMarketDataType(MDT_LIVE);

        return contracts;
    }

    /**
     * Spot resolution — three levels, never fails if strikes exist:
     * 1. providedSpot from frontend (came from a recent fetchChainParams call) — fastest, use it
     * 2. IBKR underlying market data via conId — works for both IND and FUT instruments
     * 3. Median of available strikes — always present; OTM% will be approximate but chain is shown
     */
    double resolveSpot(Instrument instrument, ChainParams params, Double providedSpot) {
        if (providedSpot != null && providedSpot > 0) {
            log.info("Using provided spot for {}: {}", instrument.getSymbol(), providedSpot);
            return providedSpot;
        }

        try {
            TickData tick = ibkr.reqMktData(buildUnderlyingContract(instrument), WINDOW_MS).join();
            Optional<Double> ibkrSpot = extractSpot(tick, instrument.getSymbol());
            if (ibkrSpot.isPresent()) return ibkrSpot.get();
        } catch (Exception e) {
            log.warn("Underlying market data unavailable for {}: {}", instrument.getSymbol(), e.getMessage());
        }

        List<Double> sorted = params.strikes().stream().sorted().toList();
        if (!sorted.isEmpty()) {
            double median = sorted.get(sorted.size() / 2);
            log.warn("{}: no live spot — using median strike {} as proxy (OTM% approximate)",
                    instrument.getSymbol(), median);
            return median;
        }

        throw new RuntimeException("No strikes available for " + instrument.getSymbol());
    }

    /** Extracts last or close price from a tick. Returns empty if no usable price is available. */
    private Optional<Double> extractSpot(TickData tick, String symbol) {
        if (tick == null) return Optional.empty();
        Double price = (tick.last()  != null && tick.last()  > 0) ? tick.last()
                     : (tick.close() != null && tick.close() > 0) ? tick.close()
                     : null;
        if (price != null)
            log.info("Spot for {} from underlying market data: {}", symbol, price);
        return Optional.ofNullable(price);
    }

    /**
     * Builds a contract that uniquely identifies the underlying by conId.
     * This works for both index (IND) and futures-based (FUT) underlyings — e.g. DAX index
     * and ESTX50 futures — without hardcoding secType="IND" which would fail for FUT instruments.
     */
    private Contract buildUnderlyingContract(Instrument instrument) {
        Contract c = new Contract();
        c.conid(instrument.getConId());
        c.exchange(instrument.getExchange());
        return c;
    }

    // ═══════════════════════════════════════════════════════════
    // IBKR fetch
    // ═══════════════════════════════════════════════════════════

    private List<OptionContract> fetchFromIbkr(Instrument instrument,
                                                ChainParams params,
                                                double spot,
                                                String expiryFilter,
                                                boolean includeMonthly,
                                                boolean includeWeekly,
                                                String strikeFilter,
                                                double strikeRangePct,
                                                int tickTimeoutMs,
                                                boolean marketHours) {
        long fetchStart = System.currentTimeMillis();

        // Filter expiries by requested type
        List<String> expiries = schedule.filterExpiries(
                params.expirations(), MAX_DTE_DAYS, includeMonthly, includeWeekly);
        if (expiryFilter != null) {
            // Match by YYYYMM prefix — frontend sends any date in the target month
            String ym = expiryFilter.length() >= 6 ? expiryFilter.substring(0, 6) : expiryFilter;
            expiries = expiries.stream().filter(e -> e.startsWith(ym)).toList();
            log.info("Expiry month filter {}: {} matches", ym, expiries.size());
        } else {
            expiries = expiries.subList(0, Math.min(expiries.size(), instrument.getMaxExpiries()));
        }
        log.info("Expiries ({}): {}", expiries.size(), expiries);

        // Filter strikes: ACTIVE = within ±strikeRangePct of spot; ALL = every theoretical strike
        List<Double> allAvailableStrikes = params.strikes().stream().sorted().toList();
        log.info("IBKR strikes available: {} total, range {}-{}",
                allAvailableStrikes.size(),
                allAvailableStrikes.isEmpty() ? "n/a" : allAvailableStrikes.get(0),
                allAvailableStrikes.isEmpty() ? "n/a" : allAvailableStrikes.get(allAvailableStrikes.size() - 1));

        List<Double> strikes = allAvailableStrikes.stream()
                .filter(s -> "ALL".equals(strikeFilter)
                             || Math.abs(s - spot) / spot * 100.0 <= strikeRangePct)
                .toList();
        log.info("Spot={} strikeFilter={} pct={} → {} strikes (min={} max={})",
                spot, strikeFilter, strikeRangePct, strikes.size(),
                strikes.isEmpty() ? "n/a" : strikes.get(0),
                strikes.isEmpty() ? "n/a" : strikes.get(strikes.size() - 1));

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
        // Log first contract spec so we can verify exchange/tradingClass are correct
        if (!requests.isEmpty()) {
            Contract sample = buildOptionContract(requests.get(0), instrument);
            log.info("Sample contract: symbol={} exchange={} currency={} tradingClass={} expiry={} strike={} right={}",
                    sample.symbol(), sample.exchange(), sample.currency(), sample.tradingClass(),
                    sample.lastTradeDateOrContractMonth(), sample.strike(), sample.right());
        }

        // Counter for no-data diagnostics — logs first 5 empty ticks in detail
        AtomicInteger noDataSampleLeft = new AtomicInteger(5);

        // Submit all fetches through the rate limiter. submit() enqueues immediately
        // and returns a promise; the scheduler fires one reqMktData per RATE_MS.
        List<CompletableFuture<Optional<OptionContract>>> futures = requests.stream()
                .map(req -> rateLimiter.submit(() ->
                        ibkr.reqMktData(buildOptionContract(req, instrument), tickTimeoutMs)
                                .thenApply(tick -> {
                                    if (!tick.hasData() && noDataSampleLeft.getAndDecrement() > 0) {
                                        log.debug("NO-DATA {}/{}/{}: bid={} ask={} last={} close={} iv={} delta={} greeks={}",
                                                req.expiry(), req.strike(), req.type(),
                                                tick.bid(), tick.ask(), tick.last(), tick.close(),
                                                tick.impliedVol(), tick.delta(), tick.greeksReceived());
                                    }
                                    // Always include the contract — empty data shows as dashes in the UI.
                                    // The frontend 'Quoted' display filter handles visibility.
                                    return Optional.of(toOptionContract(req, tick, spot, multiplier, marketHours));
                                })
                                .exceptionally(ex -> {
                                    log.warn("Tick fetch failed {}/{}/{}: {}",
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
        long noDataCount = result.stream()
                .filter(c -> c.getBid() == null && c.getAsk() == null
                             && c.getLast() == null && c.getClose() == null
                             && !"IBKR".equals(c.getGreeksSource())).count();
        log.info("Chain complete — {} contracts ({} after dedup), {} no-data, in {}ms",
                chain.size(), result.size(), noDataCount, System.currentTimeMillis() - fetchStart);
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
    // Mapping — converts raw IBKR tick + request into an OptionContract
    // ═══════════════════════════════════════════════════════════

    private OptionContract toOptionContract(ContractRequest req, TickData tick,
                                             double spot, int multiplier, boolean marketHours) {
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
                .bidSize(tick.bidSize())
                .askSize(tick.askSize())
                .volume(tick.volume())
                .openInterest(tick.openInterest())
                .greeksSource(tick.greeksReceived() ? "IBKR" : "NONE")
                .confidenceScore(calculateConfidenceScore(tick, marketHours))
                .build();
    }

    // ═══════════════════════════════════════════════════════════
    // Confidence scoring
    // ═══════════════════════════════════════════════════════════

    /**
     * 3 = Live  : market open, real-time bid + ask Greeks
     * 2 = Model : market closed, IBKR server-side Black-Scholes (Field 13)
     * 1 = Stale : Greeks present but no bid/ask size (illiquid or wide spread)
     * 0 = None  : no Greeks received at all
     */
    private int calculateConfidenceScore(TickData tick, boolean marketOpen) {
        if (!tick.greeksReceived()) return 0;
        if (marketOpen && tick.bid() != null && tick.ask() != null) return 3;
        if (!marketOpen) return 2;
        return 1; // market open but bid or ask missing — illiquid strike
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
