package com.metertrail.example.model;

import lombok.Getter;
import lombok.Setter;
import java.util.List;

@Getter
@Setter
public class BatchRequest {
    private String schemaVersion;
    private Agent agent;
    private List<Event<?>> events;
}
