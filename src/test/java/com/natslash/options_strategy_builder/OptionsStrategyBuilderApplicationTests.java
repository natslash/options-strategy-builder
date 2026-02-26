package com.natslash.options_strategy_builder;

import com.natslash.options_strategy_builder.service.IbkrClientService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
class OptionsStrategyBuilderApplicationTests {

    // Prevent IbkrClientService @PostConstruct from attempting a real TCP connection
    @MockitoBean
    IbkrClientService ibkrClientService;

    @Test
    void contextLoads() {
    }

}
