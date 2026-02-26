package com.natslash.options_strategy_builder.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "instruments")
public class Instrument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String symbol;
    private String name;
    private String exchange;
    private String currency;
    private Integer conId;
    private Integer multiplier;
    private String tradingClass;
    private Integer strikeRange;
    private Integer maxExpiries;
    private Boolean active = true;

    @Column(updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}