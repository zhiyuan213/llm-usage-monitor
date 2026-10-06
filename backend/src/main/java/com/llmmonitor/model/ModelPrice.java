package com.llmmonitor.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
public class ModelPrice {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String provider;
    private String model;
    private double inputPricePerMillion;
    private double outputPricePerMillion;
    private Instant effectiveDate;
}
