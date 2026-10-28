package com.metertrail.example.model;

import lombok.Getter;
import lombok.Setter;
@Getter
@Setter
public class Event<T> {
    private String eventId;
    private Long revision;
    private String type;
    private String objectId;
    private String traceId;
    private String spanId;
    private String startedAt;
    private T data;
}
