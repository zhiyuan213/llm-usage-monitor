package com.metertrail.example.model;

import com.fasterxml.jackson.annotation.JsonInclude;

// Unknown usage must remain explicit null; it must not disappear from JSON.
@JsonInclude(JsonInclude.Include.ALWAYS)
public class Usage {
    private Long inputTokens;
    private Long outputTokens;
    private Long cachedInputTokens;

    public Long getInputTokens() {
        return inputTokens;
    }

    public void setInputTokens(Long inputTokens) {
        this.inputTokens = inputTokens;
    }

    public Long getOutputTokens() {
        return outputTokens;
    }

    public void setOutputTokens(Long outputTokens) {
        this.outputTokens = outputTokens;
    }

    public Long getCachedInputTokens() {
        return cachedInputTokens;
    }

    public void setCachedInputTokens(Long cachedInputTokens) {
        this.cachedInputTokens = cachedInputTokens;
    }
}
