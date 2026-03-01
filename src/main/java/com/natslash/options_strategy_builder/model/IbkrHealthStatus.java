package com.natslash.options_strategy_builder.model;

import java.time.Instant;

public record IbkrHealthStatus(
        boolean healthy,
        boolean socketConnected,
        boolean dataReceived,
        String  detail,
        String  dataMode,   // "LIVE" or "FROZEN"
        Instant checkedAt
) {
    public static IbkrHealthStatus healthy(String dataMode, Instant at) {
        return new IbkrHealthStatus(true, true, true, null, dataMode, at);
    }

    public static IbkrHealthStatus unhealthy(boolean socket, boolean data, String detail, String dataMode, Instant at) {
        return new IbkrHealthStatus(false, socket, data, detail, dataMode, at);
    }
}
