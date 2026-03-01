package com.natslash.options_strategy_builder.service;

import com.ib.client.Contract;
import com.natslash.options_strategy_builder.entity.Instrument;
import com.natslash.options_strategy_builder.model.ChainParams;
import com.natslash.options_strategy_builder.model.TickData;
import com.natslash.options_strategy_builder.repository.InstrumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * IBKR-agnostic market data access for domain services.
 * Domain services (e.g. StrategyService) depend on this, not on IbkrClientService.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketDataService {

    private static final int    FUTURES_TIMEOUT_MS   = 1500;
    private static final long   FUTURES_CACHE_TTL_MS = 60_000L; // 60 seconds
    private static final int    MDT_LIVE             = 1;
    private static final int    MDT_FROZEN           = 2;

    private final IbkrClientService    ibkr;
    private final InstrumentRepository instrumentRepository;
    private final TradingSchedule      schedule;

    private final Map<Long, CachedFuturesPrice> futuresCache = new ConcurrentHashMap<>();
    private final Map<Long, CachedFuturesPrice> spotCache    = new ConcurrentHashMap<>();

    private record CachedFuturesPrice(double price, long expiresAt) {
        boolean isValid() { return System.currentTimeMillis() < expiresAt; }
    }

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
                    ibkr.reqChainParams(instrument.getSymbol(), instrument.getSecType(), instrument.getConId()).join();
            return params.strikes();
        } catch (Exception e) {
            log.warn("Could not fetch strikes for instrumentId={}: {}", instrumentId, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Returns the futures price for the given instrument using its {@code futuresConId}.
     * Result is cached for 60 seconds.
     *
     * @return futures price, or null if the instrument has no futuresConId or IBKR is unavailable
     */
    public Double getFuturesPrice(Long instrumentId) {
        if (instrumentId == null) return null;

        Instrument instrument = instrumentRepository.findById(instrumentId).orElse(null);
        if (instrument == null || instrument.getFuturesConId() == null) return null;

        CachedFuturesPrice cached = futuresCache.get(instrumentId);
        if (cached != null && cached.isValid()) {
            log.debug("Futures price cache hit for instrumentId={}: {}", instrumentId, cached.price());
            return cached.price();
        }

        try {
            ibkr.reqMarketDataType(schedule.isMarketHours() ? MDT_LIVE : MDT_FROZEN);

            Contract futContract = new Contract();
            futContract.conid(instrument.getFuturesConId());
            futContract.exchange(instrument.getExchange());

            TickData tick = ibkr.reqMktData(futContract, FUTURES_TIMEOUT_MS).join();
            Double price = extractPrice(tick);
            if (price == null) {
                log.warn("No futures price from IBKR for instrumentId={}", instrumentId);
                return null;
            }

            futuresCache.put(instrumentId, new CachedFuturesPrice(price, System.currentTimeMillis() + FUTURES_CACHE_TTL_MS));
            log.info("Futures price for instrumentId={}: {}", instrumentId, price);
            return price;

        } catch (Exception e) {
            log.warn("Could not fetch futures price for instrumentId={}: {}", instrumentId, e.getMessage());
            return null;
        }
    }

    /**
     * Returns the current underlying spot price for the given instrument using its {@code conId}.
     * Works for both IND and STK instruments. Result is cached for 60 seconds.
     *
     * @return spot price, or null if the instrument is not found or IBKR is unavailable
     */
    public Double getSpot(Long instrumentId) {
        if (instrumentId == null) return null;

        Instrument instrument = instrumentRepository.findById(instrumentId).orElse(null);
        if (instrument == null) return null;

        CachedFuturesPrice cached = spotCache.get(instrumentId);
        if (cached != null && cached.isValid()) {
            log.debug("Spot cache hit for instrumentId={}: {}", instrumentId, cached.price());
            return cached.price();
        }

        try {
            ibkr.reqMarketDataType(schedule.isMarketHours() ? MDT_LIVE : MDT_FROZEN);

            Contract c = new Contract();
            c.conid(instrument.getConId());
            c.exchange(instrument.getExchange());

            TickData tick = ibkr.reqMktData(c, FUTURES_TIMEOUT_MS).join();
            Double price = extractPrice(tick);
            if (price == null) {
                log.warn("No spot price from IBKR for instrumentId={}", instrumentId);
                return null;
            }

            spotCache.put(instrumentId,
                    new CachedFuturesPrice(price, System.currentTimeMillis() + FUTURES_CACHE_TTL_MS));
            log.info("Spot for instrumentId={}: {}", instrumentId, price);
            return price;

        } catch (Exception e) {
            log.warn("Could not fetch spot for instrumentId={}: {}", instrumentId, e.getMessage());
            return null;
        }
    }

    private Double extractPrice(TickData tick) {
        if (tick == null) return null;
        if (tick.last()  != null && tick.last()  > 0) return tick.last();
        if (tick.close() != null && tick.close() > 0) return tick.close();
        return null;
    }
}
