package com.aliyun.autowonder.dispatch.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Getter
@Setter
public class RuntimeTraceVO {
    private String schemaVersion;
    private String source;
    private Long dispatchId;
    private String runtimeId;
    private String provider;
    private boolean changed = true;
    private Long lastSeq;
    private TokenUsage tokenUsage = new TokenUsage();
    private List<Event> events = new ArrayList<>();
    private List<Session> sessions = new ArrayList<>();

    @Getter
    @Setter
    public static class Event {
        private String eventId;
        private Long seq;
        private String eventType;
        private String eventTime;
        private Map<String, Object> detail;
    }

    @Getter
    @Setter
    public static class Session {
        private String sessionId;
        private String parentSessionId;
        private String provider;
        private String status;
        private String startedAt;
        private String endedAt;
        private Long durationMs;
        private TokenUsage tokenUsage = new TokenUsage();
        private List<String> eventIds = new ArrayList<>();
        private List<Boundary> boundaries = new ArrayList<>();
        private List<Turn> turns = new ArrayList<>();
    }

    @Getter
    @Setter
    public static class Turn {
        private String traceId;
        private String turnId;
        private String stepId;
        private String stepName;
        private String status;
        private String startedAt;
        private String endedAt;
        private Long durationMs;
        private String prompt;
        private String systemPrompt;
        private String output;
        private String providerCoverage;
        private TokenUsage tokenUsage = new TokenUsage();
        private TokenUsage usage = new TokenUsage();
        private List<ContextFile> contextFiles = new ArrayList<>();
        private List<Observation> observations = new ArrayList<>();
        private List<String> eventIds = new ArrayList<>();
        private List<Span> spans = new ArrayList<>();
    }

    @Getter
    @Setter
    public static class Observation {
        private String observationId;
        private String parentObservationId;
        private String type;
        private String name;
        private String status;
        private String startedAt;
        private String endedAt;
        private Long durationMs;
        private String model;
        private Object input;
        private Object output;
        private Object error;
        private boolean orphan;
        private TokenUsage usage = new TokenUsage();
        private List<Observation> children = new ArrayList<>();
    }

    @Getter
    @Setter
    public static class ContextFile {
        private String role;
        private String name;
        private String mediaType;
        private Long sizeBytes;
        private String sha256;
        private String contentRef;
        private boolean previewable;
    }

    @Getter
    @Setter
    public static class Span {
        private String spanId;
        private String parentSpanId;
        private String kind;
        private String name;
        private String status;
        private String startedAt;
        private String endedAt;
        private Long durationMs;
        private String model;
        private String inputSummary;
        private String outputSummary;
        private Object input;
        private String output;
        private String content;
        private String errorCategory;
        private TokenUsage tokenUsage = new TokenUsage();
        private List<String> eventIds = new ArrayList<>();
    }

    @Getter
    @Setter
    public static class Boundary {
        private String eventId;
        private String kind;
        private String type;
        private String eventTime;
        private String time;
        private String label;
        private Map<String, Object> detail;
    }

    @Getter
    @Setter
    public static class TokenUsage {
        private boolean available;
        private String availability;
        private String source;
        private Double credits;
        private long inputTokens;
        private long outputTokens;
        private long reasoningTokens;
        private long cacheReadTokens;
        private long cacheWriteTokens;
        private long totalTokens;
    }
}
