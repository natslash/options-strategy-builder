package com.natslash.options_strategy_builder.service;

import com.ib.client.Contract;
import com.natslash.options_strategy_builder.entity.Instrument;
import com.natslash.options_strategy_builder.model.HistoricalBar;
import com.natslash.options_strategy_builder.model.IVRankResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Computes IV Rank for an instrument using 52-week historical IV and 30-day HV from IBKR.
 * Results are cached in-memory with a 4-hour TTL.
 *
 * <p>Returns null (non-blocking) when IBKR is unavailable — callers must null-check.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IVRankService {

    private static final long CACHE_TTL_MS = 4 * 60 * 60 * 1000L; // 4 hours

    private final IbkrClientService ibkr;

    private final Map<Long, CachedIVRank> cache = new ConcurrentHashMap<>();

    private record CachedIVRank(IVRankResult result, long expiresAt) {
        boolean isValid() { return System.currentTimeMillis() < expiresAt; }
    }

    /**
     * Returns the IV rank for the given instrument, or null if IBKR is unavailable.
     */
    public IVRankResult getIVRank(Instrument instrument) {
        CachedIVRank cached = cache.get(instrument.getId());
        if (cached != null && cached.isValid()) {
            log.debug("IV rank cache hit for {}", instrument.getSymbol());
            return cached.result();
        }

        try {
            Contract contract = buildUnderlyingContract(instrument);

            // 252 daily IV bars over 1 year
            List<HistoricalBar> ivBars = ibkr.reqHistoricalData(
                    contract, "1 Y", "1 day", "OPTION_IMPLIED_VOLATILITY").join();

            // 21 daily HV bars over 1 month
            List<HistoricalBar> hvBars = ibkr.reqHistoricalData(
                    contract, "1 M", "1 day", "HISTORICAL_VOLATILITY").join();

            if (ivBars.isEmpty()) {
                log.warn("No IV historical data for {} — skipping IV rank", instrument.getSymbol());
                return null;
            }

            double currentIv = ivBars.get(ivBars.size() - 1).close();
            double min52w = ivBars.stream().mapToDouble(HistoricalBar::close).min().orElse(currentIv);
            double max52w = ivBars.stream().mapToDouble(HistoricalBar::close).max().orElse(currentIv);

            double ivRank = (max52w == min52w) ? 0.0
                    : (currentIv - min52w) / (max52w - min52w);

            double hv30 = hvBars.isEmpty() ? currentIv
                    : hvBars.stream().mapToDouble(HistoricalBar::close).average().orElse(currentIv);
            double hvRatio = (hv30 > 0) ? currentIv / hv30 : 1.0;

            IVRankResult result = new IVRankResult(currentIv, ivRank, hvRatio, IVRankResult.labelFor(ivRank));
            cache.put(instrument.getId(), new CachedIVRank(result, System.currentTimeMillis() + CACHE_TTL_MS));
            log.info("IV rank for {}: rank={} label={} hvRatio={}",
                    instrument.getSymbol(), ivRank, result.label(), hvRatio);
            return result;

        } catch (Exception e) {
            log.warn("IV rank unavailable for {} — continuing without it: {}",
                    instrument.getSymbol(), e.getMessage());
            return null;
        }
    }

    private Contract buildUnderlyingContract(Instrument instrument) {
        Contract c = new Contract();
        c.conid(instrument.getConId());
        c.exchange(instrument.getExchange());
        return c;
    }
}
