package com.metertrail.example.model;

import lombok.Getter;
import lombok.Setter;
@Getter
@Setter
public class TraceData {
    private String name;
    private String status;
    private Long durationMs;
    private String userId;
    private String customerId;
    private String feature;
}
