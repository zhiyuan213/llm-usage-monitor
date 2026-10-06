package com.llmmonitor.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
public class LlmUsage {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String provider;
    private String model;
    private long inputTokens;
    private long outputTokens;
    private double cost;
    private Instant requestTime;
}
