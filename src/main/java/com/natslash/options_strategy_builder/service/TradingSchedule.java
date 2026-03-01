package com.natslash.options_strategy_builder.service;

import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Encapsulates trading-calendar logic: market-hours detection and
 * option-expiry filtering.
 */
@Component
public class TradingSchedule {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final ZoneId            CET = ZoneId.of("Europe/Berlin");

    public boolean isMarketHours() {
        ZonedDateTime now     = ZonedDateTime.now(CET);
        int           timeNow = now.getHour() * 100 + now.getMinute();
        return now.getDayOfWeek() != DayOfWeek.SATURDAY
                && now.getDayOfWeek() != DayOfWeek.SUNDAY
                && timeNow >= 900
                && timeNow <= 1730;
    }

    /**
     * Returns expiries within {@code maxDte} days matching the requested types.
     * Set {@code includeMonthly} for third-Friday monthlies; {@code includeWeekly} for all other Fridays.
     */
    public List<String> filterExpiries(List<String> expirations, int maxDte,
                                        boolean includeMonthly, boolean includeWeekly) {
        LocalDate today  = LocalDate.now();
        LocalDate maxDay = today.plusDays(maxDte);
        return expirations.stream()
                .filter(e -> {
                    LocalDate d = LocalDate.parse(e, FMT);
                    if (!d.isAfter(today) || d.isAfter(maxDay))  return false;
                    if (d.getDayOfWeek() != DayOfWeek.FRIDAY)    return false;
                    boolean monthly = isThirdFriday(d);
                    return (monthly && includeMonthly) || (!monthly && includeWeekly);
                })
                .sorted()
                .toList();
    }

    /** Returns expiries that are monthly (third-Friday) and within {@code maxDte} days. */
    public List<String> filterMonthlyExpiries(List<String> expirations, int maxDte) {
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
}
