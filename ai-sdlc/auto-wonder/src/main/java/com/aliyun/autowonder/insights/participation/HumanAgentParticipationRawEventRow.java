package com.aliyun.autowonder.insights.participation;

import java.util.Date;

public class HumanAgentParticipationRawEventRow {

    private long workitemId;
    private String title;
    private Date workitemCreatedAt;
    private long eventId;
    private String eventType;
    private String toVal;
    private String inferredToType;
    private String detailJson;
    private Date eventAt;
    private boolean terminal;

    public long getWorkitemId() {
        return workitemId;
    }

    public void setWorkitemId(long workitemId) {
        this.workitemId = workitemId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public Date getWorkitemCreatedAt() {
        return workitemCreatedAt;
    }

    public void setWorkitemCreatedAt(Date workitemCreatedAt) {
        this.workitemCreatedAt = workitemCreatedAt;
    }

    public long getEventId() {
        return eventId;
    }

    public void setEventId(long eventId) {
        this.eventId = eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getToVal() {
        return toVal;
    }

    public void setToVal(String toVal) {
        this.toVal = toVal;
    }

    public String getInferredToType() {
        return inferredToType;
    }

    public void setInferredToType(String inferredToType) {
        this.inferredToType = inferredToType;
    }

    public String getDetailJson() {
        return detailJson;
    }

    public void setDetailJson(String detailJson) {
        this.detailJson = detailJson;
    }

    public Date getEventAt() {
        return eventAt;
    }

    public void setEventAt(Date eventAt) {
        this.eventAt = eventAt;
    }

    public boolean isTerminal() {
        return terminal;
    }

    public void setTerminal(boolean terminal) {
        this.terminal = terminal;
    }
}
