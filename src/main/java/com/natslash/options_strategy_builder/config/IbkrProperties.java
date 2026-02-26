package com.natslash.options_strategy_builder.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import lombok.Data;

@Data
@Component
@ConfigurationProperties(prefix = "ibkr")
public class IbkrProperties {
    private String host = "127.0.0.1";
    private int port = 4001;
    private int clientId = 3;
}
