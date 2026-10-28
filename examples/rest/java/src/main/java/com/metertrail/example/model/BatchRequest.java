package com.metertrail.example.model;

import java.util.List;

public class BatchRequest {
    private String schemaVersion;
    private Agent agent;
    private List<Event<?>> events;

    public String getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(String schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    public Agent getAgent() {
        return agent;
    }

    public void setAgent(Agent agent) {
        this.agent = agent;
    }

    public List<Event<?>> getEvents() {
        return events;
    }

    public void setEvents(List<Event<?>> events) {
        this.events = events;
    }
}
