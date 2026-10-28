package com.metertrail.example.model;

import lombok.Getter;
import lombok.Setter;
@Getter
@Setter
public class ObservationData {
    private String name;
    private String observationType;
    private String status;
    private Long durationMs;
    private String provider;
    private String model;
    private Usage usage;
}
