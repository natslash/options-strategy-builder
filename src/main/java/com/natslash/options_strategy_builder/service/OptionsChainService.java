package com.natslash.options_strategy_builder.service;

import com.ib.client.Contract;
import com.ib.client.Types;
import com.natslash.options_strategy_builder.entity.Instrument;
import com.natslash.options_strategy_builder.model.ChainFilterParams;
import com.natslash.options_strategy_builder.model.ChainParams;
import com.natslash.options_strategy_builder.model.OptionContract;
import com.natslash.options_strategy_builder.model.TickData;
import com.natslash.options_strategy_builder.model.TradingClassParams;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class OptionsChainService {

    // Collection window per reqMktData subscription. Greeks (tickOptionComputation fields 10/11/13)
    // arrive after price ticks — 3000ms ensures they are captured before completeOnTimeout fires.
    private static final int    WINDOW_MS              = 3000;
    private static final int    MAX_DTE_DAYS           = 180;
    // Hard cap per side (N below ATM + N above ATM). Total per expiry = cap*2+1.
    // Math: (cap*2)*20ms + WINDOW_MS = ~3.5s market hours, ~5.5s off-hours per expiry at cap=50.
    // Server-side cap — upper bound for user-selectable strikeCount (per-side).
    private static final int    MAX_STRIKES_PER_EXPIRY = 50;
    private static final long   PARAMS_CACHE_TTL_MS    = 60 * 60 * 1000L; // 1 hour
    private static final int    DEFAULT_STRIKE_RANGE   = 10; // ±10% of spot when instrument.strikeRange is null
    private static final DateTimeFormatter FMT         = DateTimeFormatter.ofPattern("yyyyMMdd");

    // IBKR market data types
    private static final int MDT_LIVE           = 1;
    // MDT=4: works for real-time AND delayed-only subscribers. IBKR auto-uses live/frozen data when a
    // real-time subscription exists; otherwise returns delayed-frozen data with Greeks in fields 80/81/83.
    private static final int MDT_DELAYED_FROZEN = 4;

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

    public ChainFilterParams fetchChainParams(Instrument instrument) throws Exception {
        boolean marketHours = schedule.isMarketHours();
        ibkr.reqMarketDataType(marketHours ? MDT_LIVE : MDT_DELAYED_FROZEN);

        // Params first, then spot. Firing both concurrently caused the spot tick to arrive
        // after the timeout on a cold cache — reqSecDefOptParams (up to 30s) competes
        // for the same IBKR connection and delays tickPrice callbacks.
        ChainParams params = getCachedParams(instrument);
        // Always use a generous timeout here: after reqSecDefOptParams the connection needs
        // time to settle before IBKR responds to the next subscription. Off-hours adds extra.
        int spotTimeout = marketHours ? WINDOW_MS + 2000 : WINDOW_MS + 5000;
        TickData spotTick = ibkr.reqUnderlyingPrice(buildUnderlyingContract(instrument), spotTimeout).join();
        List<Double> sortedStrikes = params.allStrikes().stream().sorted().toList();
        Optional<Double> spotOpt = extractSpot(spotTick, instrument.getSymbol());
        if (spotOpt.isEmpty())
            log.warn("{}: spot unavailable after chain params fetch (timeout={}ms) — returning params without spot window. " +
                    "IBKR connection may still be warming up; retry shortly.", instrument.getSymbol(), spotTimeout);
        double spot = spotOpt.orElse(0.0);

        List<String> expiries = params.allExpirations().stream().sorted().toList();
        DoubleSummaryStatistics stats = params.allStrikes().stream()
                .mapToDouble(Double::doubleValue).summaryStatistics();
        int range = instrument.getStrikeRange() != null ? instrument.getStrikeRange() : DEFAULT_STRIKE_RANGE;
        List<Double> windowStrikes = strikeByRange(sortedStrikes, spot, range);
        log.info("ChainParams for {}: {} expiries, strikes {}-{}, spot={}, window={}",
                instrument.getSymbol(), expiries.size(), stats.getMin(), stats.getMax(), spot, windowStrikes.size());
        return new ChainFilterParams(spot, expiries, stats.getMin(), stats.getMax(),
                (int) stats.getCount(), windowStrikes);
    }

    /**
     * Public accessor for cached ChainParams — used by diagnostic endpoints.
     * Delegates to {@link #getCachedParams} with the same fallback semantics.
     */
    public ChainParams chainParamsFor(Instrument instrument) throws Exception {
        return getCachedParams(instrument);
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
                    instrument.getSymbol(), secType, instrument.getConId(), instrument.getExchange()).join();
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
                                            String strikeFilter, int strikeCount)
            throws Exception {

        // MDT=4 (DELAYED_FROZEN) off-hours: works for both real-time and delayed-only subscribers.
        // IBKR delivers Greeks as fields 80/81/83 (delayed) or 10/11/13 (live, if subscription exists).
        boolean marketHours = schedule.isMarketHours();
        if (!marketHours) {
            ibkr.reqMarketDataType(MDT_DELAYED_FROZEN);
            log.info("Outside market hours — using delayed-frozen data (MDT=4)");
        } else {
            ibkr.reqMarketDataType(MDT_LIVE);
        }

        // Fire spot async before getCachedParams so they overlap.
        // On the common warm-cache path getCachedParams returns instantly; on a cold cache
        // both calls run concurrently, saving up to spotTimeout ms.
        int spotTimeout = marketHours ? WINDOW_MS : WINDOW_MS + 3500;
        CompletableFuture<TickData> spotFut = ibkr.reqUnderlyingPrice(
                buildUnderlyingContract(instrument), spotTimeout);
        ChainParams params = getCachedParams(instrument);
        double spot = resolveSpot(instrument, params, providedSpot, spotFut);

        List<OptionContract> contracts = fetchFromIbkr(
                instrument, params, spot, expiry, includeMonthly, includeWeekly, strikeFilter, strikeCount, spotTimeout, marketHours);

        // Reset to live so unrelated reqMktData calls (e.g. spot fetch) get live data
        ibkr.reqMarketDataType(MDT_LIVE);

        return contracts;
    }

    /**
     * Spot resolution using a pre-fetched future (allows the caller to fire the request
     * concurrently with other work before joining here).
     */
    double resolveSpot(Instrument instrument, ChainParams params, Double providedSpot,
                       CompletableFuture<TickData> spotFut) {
        try {
            TickData tick = spotFut.join();
            Optional<Double> ibkrSpot = extractSpot(tick, instrument.getSymbol());
            if (ibkrSpot.isPresent()) return ibkrSpot.get();
        } catch (Exception e) {
            log.warn("Underlying market data unavailable for {}: {}", instrument.getSymbol(), e.getMessage());
        }

        if (providedSpot != null && providedSpot > 0) {
            log.warn("{}: no IBKR spot — falling back to provided spot: {}", instrument.getSymbol(), providedSpot);
            return providedSpot;
        }

        throw new RuntimeException("No spot price available for " + instrument.getSymbol()
                + " — ensure IBGW is connected and instrument has market data");
    }

    /** Convenience overload — fires the IBKR request internally. Used by tests and fetchChainParams. */
    double resolveSpot(Instrument instrument, ChainParams params, Double providedSpot) {
        int spotTimeout = schedule.isMarketHours() ? WINDOW_MS : WINDOW_MS + 3500;
        return resolveSpot(instrument, params, providedSpot,
                ibkr.reqUnderlyingPrice(buildUnderlyingContract(instrument), spotTimeout));
    }

    /**
     * Extracts spot price from a tick. Priority: last → close → mid(bid, ask).
     * Cash indices (SPX, ESTX50 IND) have no traded last/close — IBKR only delivers
     * bid/ask for them, so mid is used as the final fallback.
     */
    private Optional<Double> extractSpot(TickData tick, String symbol) {
        if (tick == null) return Optional.empty();
        Double price;
        if (tick.last() != null && tick.last() > 0)
            price = tick.last();
        else if (tick.close() != null && tick.close() > 0)
            price = tick.close();
        else if (tick.bid() != null && tick.ask() != null && tick.bid() > 0 && tick.ask() > 0)
            price = (tick.bid() + tick.ask()) / 2.0;
        else
            price = null;
        if (price != null)
            log.info("Spot for {} from underlying market data: {}", symbol, price);
        return Optional.ofNullable(price);
    }

    /**
     * Builds a contract for the underlying spot price subscription.
     * Prefers futuresConId (e.g. FESX for ESTX50) when set — futures contracts have broader
     * market data subscriptions and return live spot prices more reliably than index cash contracts.
     * Falls back to instrument conId for instruments without a linked futures contract.
     *
     * <p>Cash indices (ESTX50, DAX, SPX) have no traded last/close — IBKR returns only bid/ask.
     * {@link #tryCompleteEarly} and {@link #extractSpot} handle these via the mid(bid,ask) fallback.
     */
    private Contract buildUnderlyingContract(Instrument instrument) {
        Contract c = new Contract();
        boolean hasFutures = instrument.getFuturesConId() != null;
        Integer cid = hasFutures ? instrument.getFuturesConId() : instrument.getConId();
        String secType = hasFutures ? "FUT" : instrument.getSecType() != null ? instrument.getSecType() : "IND";
        log.info("buildUnderlyingContract for {}: conId={} ({}) secType={} exchange={}",
                instrument.getSymbol(), cid, hasFutures ? "futuresConId" : "instrument conId",
                secType, instrument.getExchange());
        c.conid(cid);
        c.secType(secType);
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
                                                int strikeCount,
                                                int tickTimeoutMs,
                                                boolean marketHours) {
        long fetchStart = System.currentTimeMillis();

        // Filter expiries by requested type
        List<String> expiries = schedule.filterExpiries(
                params.allExpirations(), MAX_DTE_DAYS, includeMonthly, includeWeekly);
        if (expiryFilter != null) {
            // Match by YYYYMM prefix — frontend sends any date in the target month
            String ym = expiryFilter.length() >= 6 ? expiryFilter.substring(0, 6) : expiryFilter;
            expiries = expiries.stream().filter(e -> e.startsWith(ym)).toList();
            log.info("Expiry month filter {}: {} matches", ym, expiries.size());
        } else {
            expiries = expiries.subList(0, Math.min(expiries.size(), instrument.getMaxExpiries()));
        }
        log.info("Expiries ({}): {}", expiries.size(), expiries);

        // Per-expiry strike resolution: use each expiry's own tradingClass grid to avoid
        // IBKR error 200 from requesting strikes that don't exist in that series.
        // The union (allStrikes) includes e.g. monthly 25pt strikes invalid for weekly expiries.
        //
        // strikeCount is per-side: strikeCount=15 → 15 below ATM + ATM + 15 above ATM.
        int range      = instrument.getStrikeRange() != null ? instrument.getStrikeRange() : DEFAULT_STRIKE_RANGE;
        int capPerSide = Math.min(strikeCount, MAX_STRIKES_PER_EXPIRY);
        int totalCap   = capPerSide * 2 + 1; // ATM + N each side

        List<ContractRequest> requests = new ArrayList<>();
        for (String expiry : expiries) {
            TradingClassParams tc = params.forExpiry(expiry).orElse(null);
            String tradingClass    = tc != null ? tc.tradingClass()     : instrument.getTradingClass();
            // Use the actual options exchange from the secDefOptParams callback, not the instrument's
            // underlying exchange. Critical for SMART-routed instruments (e.g. BAYN STK) where the
            // underlying is on SMART but the options are listed on a different exchange (e.g. EUREX).
            String optExchange     = tc != null ? tc.optionsExchange()  : instrument.getExchange();
            // Get the correct strike grid for this tradingClass, then remove fine near-ATM
            // theoretical strikes that IBKR returned but have no active contract.
            List<Double> expiryStrikes = filterToActiveGrid(
                    (tc != null ? tc.strikes() : params.allStrikes()).stream().sorted().toList(), spot);

            List<Double> filtered = "ALL".equals(strikeFilter)
                    ? expiryStrikes
                    : strikeByRange(expiryStrikes, spot, range);
            if (filtered.size() > totalCap) filtered = centredSublist(filtered, spot, totalCap);

            log.info("Expiry {}: tradingClass={} exchange={} grid={} → range={} → cap={} strikes (±{} per side)",
                    expiry, tradingClass, optExchange, expiryStrikes.size(), filtered.size() == expiryStrikes.size()
                            ? filtered.size() : filtered.size() + "/" + expiryStrikes.size(),
                    filtered.size(), capPerSide);
            for (double strike : filtered) {
                requests.add(new ContractRequest(expiry, strike, "C", tradingClass, optExchange));
                requests.add(new ContractRequest(expiry, strike, "P", tradingClass, optExchange));
            }
        }
        log.info("Spot={} strikeCount={}±{}/side → {} total requests across {} expiries",
                spot, capPerSide, capPerSide, requests.size(), expiries.size());

        int multiplier = instrument.getMultiplier();
        long estimatedMs = (long)(requests.size() - 1) * RateLimitedRequestManager.RATE_MS + tickTimeoutMs;
        log.info("Fetching {} contracts — estimated ~{}s ({} req/s, {}ms window)",
                requests.size(), estimatedMs / 1000.0,
                1000 / RateLimitedRequestManager.RATE_MS, tickTimeoutMs);
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
                                    // Skip contracts with no market data — 5-point strikes deep
                                    // in the chain often have no quotes or Greeks off-hours.
                                    if (!tick.hasData()) return Optional.<OptionContract>empty();
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
        c.exchange(req.optionsExchange());
        c.currency(instrument.getCurrency());
        c.lastTradeDateOrContractMonth(req.expiry());
        c.strike(req.strike());
        c.right("C".equals(req.type()) ? Types.Right.Call : Types.Right.Put);
        c.multiplier(String.valueOf(instrument.getMultiplier()));
        c.tradingClass(req.tradingClass());
        return c;
    }

    private record ContractRequest(String expiry, double strike, String type, String tradingClass, String optionsExchange) {}

    // ═══════════════════════════════════════════════════════════
    // Strike range helper
    // ═══════════════════════════════════════════════════════════

    /**
     * Returns strikes within ±rangePercent% of spot using the instrument's actual IBKR strike grid.
     * The gap between strikes is real (e.g. 25 pts for ESTX50, 5 pts for SPX) — the count
     * emerges from the grid, not a forced window.
     * Package-private for unit testing.
     *
     * @param sorted      ascending-sorted strike list from IBKR
     * @param spot        current underlying price
     * @param rangePercent percentage of spot for the half-range (e.g. 10 → spot±10%)
     */
    static List<Double> strikeByRange(List<Double> sorted, double spot, int rangePercent) {
        if (sorted.isEmpty() || rangePercent <= 0 || spot <= 0) return Collections.emptyList();
        double lo = spot * (1.0 - rangePercent / 100.0);
        double hi = spot * (1.0 + rangePercent / 100.0);
        return sorted.stream().filter(s -> s >= lo && s <= hi).toList();
    }

    /**
     * Filters a sorted strike list to the active grid by removing fine near-ATM strikes that
     * are not on the coarser outer-zone interval. Prevents IBKR error-200 on non-existent contracts.
     *
     * <p>Algorithm:
     * <ol>
     *   <li>Compute outer step: modal gap between consecutive outer-zone strikes (outside ±10% of spot).</li>
     *   <li>Active step = outerStep / 2.</li>
     *   <li>Near-ATM strikes not divisible by activeStep are removed; outer-zone strikes are kept.</li>
     * </ol>
     *
     * <p>Example: OESX outer step=50pt → activeStep=25pt. Near-ATM 5pt strikes at 4505, 4510…
     * are removed; only 25pt-boundary strikes (4500, 4525, 4550…) are retained.
     *
     * @param sorted ascending-sorted strike list
     * @param spot   current underlying price
     */
    static List<Double> filterToActiveGrid(List<Double> sorted, double spot) {
        if (sorted.size() < 4) return sorted;
        double lo = spot * 0.9;
        double hi = spot * 1.1;

        List<Double> outer = sorted.stream().filter(s -> s < lo || s > hi).toList();
        if (outer.size() < 2) return sorted;

        // Find modal gap (multiplied by 100 to avoid floating-point key issues)
        Map<Long, Long> gapFreq = new HashMap<>();
        for (int i = 1; i < outer.size(); i++) {
            long gap = Math.round((outer.get(i) - outer.get(i - 1)) * 100);
            gapFreq.merge(gap, 1L, Long::sum);
        }
        long outerStepUnits = gapFreq.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(0L);
        if (outerStepUnits <= 0) return sorted;

        double activeStep = outerStepUnits / 200.0; // outerStep / 2 in price units
        return sorted.stream().filter(s -> {
            if (s < lo || s > hi) return true;
            double rem = s % activeStep;
            return rem < 0.01 || (activeStep - rem) < 0.01;
        }).toList();
    }

    /**
     * Returns up to {@code maxCount} strikes centred around spot from an already-sorted list.
     * Used to cap large strike lists (e.g. strikeFilter=ALL) while keeping the ATM strikes.
     */
    static List<Double> centredSublist(List<Double> sorted, double spot, int maxCount) {
        if (sorted.isEmpty() || maxCount <= 0) return Collections.emptyList();
        // Find the index of the strike closest to spot
        int atm = 0;
        double minDist = Double.MAX_VALUE;
        for (int i = 0; i < sorted.size(); i++) {
            double dist = Math.abs(sorted.get(i) - spot);
            if (dist < minDist) { minDist = dist; atm = i; }
        }
        int half  = maxCount / 2;
        int start = Math.max(0, atm - half);
        int end   = Math.min(sorted.size(), start + maxCount);
        // Shift start left if end was clamped
        start = Math.max(0, end - maxCount);
        return sorted.subList(start, end);
    }
}
