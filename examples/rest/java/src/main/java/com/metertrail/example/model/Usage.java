package com.metertrail.example.model;

import lombok.Getter;
import lombok.Setter;
import com.fasterxml.jackson.annotation.JsonInclude;

// Unknown usage must remain explicit null; it must not disappear from JSON.
@JsonInclude(JsonInclude.Include.ALWAYS)
@Getter
@Setter
public class Usage {
    private Long inputTokens;
    private Long outputTokens;
    private Long cachedInputTokens;
}
