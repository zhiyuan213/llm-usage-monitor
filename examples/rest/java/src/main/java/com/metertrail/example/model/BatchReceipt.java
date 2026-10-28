package com.metertrail.example.model;

import lombok.Getter;
import lombok.Setter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
@Getter
@Setter
public class BatchReceipt {
    private String batchId;
    private Integer received;
    private String status;
}
