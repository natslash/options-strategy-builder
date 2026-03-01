package com.natslash.options_strategy_builder;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class OptionsStrategyBuilderApplication {

	public static void main(String[] args) {
		SpringApplication.run(OptionsStrategyBuilderApplication.class, args);
	}

}
