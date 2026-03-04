package com.natslash.options_strategy_builder.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;

@Data
@Entity
@Table(name = "order_legs")
public class OrderLeg {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(nullable = false)
    private String expiry; // YYYYMMDD

    @Column(nullable = false)
    private BigDecimal strike;

    @Column(nullable = false)
    private String optionType; // "C" or "P"

    @Column(nullable = false)
    private String direction; // "LONG" or "SHORT"

    @Column(nullable = false)
    private Integer quantity;

    @Column(precision = 19, scale = 4)
    private BigDecimal premium; // per-unit premium at time of order
}