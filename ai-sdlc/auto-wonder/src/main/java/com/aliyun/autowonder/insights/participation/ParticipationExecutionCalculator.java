package com.aliyun.autowonder.insights.participation;

import com.aliyun.autowonder.dispatch.DispatchRuntimeEventDO;
import com.aliyun.autowonder.json.JSON;
import com.aliyun.autowonder.json.JSONObject;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/** Read-only reconstruction. Missing boundaries are unknown, never zero or elapsed-until-now. */
final class ParticipationExecutionCalculator {
    private static final Set<String> STARTS = Set.of("session.started", "session.resumed", "session.forked");
    private static final Set<String> ENDS = Set.of("session.interrupted", "session.completed", "session.failed", "session.cancelled");

    static void enrich(HumanAgentParticipationFact fact, List<DispatchRuntimeEventDO> events) {
        Map<Long, List<DispatchRuntimeEventDO>> dispatches = events.stream()
                .collect(Collectors.groupingBy(DispatchRuntimeEventDO::getDispatchId));
        List<Instant[]> intervals = new ArrayList<>();
        boolean observed = false, missing = false, incomplete = false, measured = false;
        for (List<DispatchRuntimeEventDO> dispatch : dispatches.values()) {
            boolean relevant = dispatch.stream().anyMatch(e -> "dispatch.observed".equals(e.getEventType())
                    && e.getEventTime() != null && e.getEventTime().toInstant().isBefore(fact.completedAt()));
            if (!relevant) continue;
            observed = true;
            Map<String, Instant> open = new HashMap<>();
            boolean sawBoundary = false;
            for (DispatchRuntimeEventDO event : dispatch) {
                String type = event.getEventType();
                if (!STARTS.contains(type) && !ENDS.contains(type)) continue;
                String sessionId = sessionId(event.getDetailJson());
                if (event.getEventTime() == null || sessionId == null) { incomplete = true; continue; }
                Instant at = event.getEventTime().toInstant();
                if (!at.isBefore(fact.completedAt()) && (STARTS.contains(type) || !open.containsKey(sessionId))) continue;
                sawBoundary = true;
                if (STARTS.contains(type)) {
                    if (open.put(sessionId, at) != null) incomplete = true;
                } else {
                    Instant start = open.remove(sessionId);
                    if (start == null || at.isBefore(start)) { incomplete = true; continue; }
                    measured = measured || !at.isBefore(fact.createdAt());
                    Instant clippedStart = start.isBefore(fact.createdAt()) ? fact.createdAt() : start;
                    Instant clippedEnd = at.isAfter(fact.completedAt()) ? fact.completedAt() : at;
                    if (clippedEnd.isAfter(clippedStart)) intervals.add(new Instant[]{clippedStart, clippedEnd});
                }
            }
            if (!sawBoundary) missing = true;
            if (!open.isEmpty()) incomplete = true;
        }
        if (observed) fact.setAgentParticipated(true);
        if (incomplete || (missing && !intervals.isEmpty())) {
            fact.setExecution(null, "INCOMPLETE");
        } else if (!observed || missing || !measured) {
            fact.setExecution(null, "MISSING");
        } else {
            // Union concurrent sessions and retries before computing wall-clock execution time.
            intervals.sort(Comparator.comparing(interval -> interval[0]));
            Instant start = null, end = null;
            long millis = 0;
            for (Instant[] interval : intervals) {
                if (end == null || interval[0].isAfter(end)) {
                    if (end != null) millis += Duration.between(start, end).toMillis();
                    start = interval[0];
                    end = interval[1];
                } else if (interval[1].isAfter(end)) {
                    end = interval[1];
                }
            }
            if (end != null) millis += Duration.between(start, end).toMillis();
            fact.setExecution(millis / 1000, "COMPLETE");
        }
    }

    private static String sessionId(String detail) {
        try {
            JSONObject json = JSON.parseObject(detail);
            String id = json == null ? null : json.getString("sessionId");
            return id == null || id.isBlank() || "null".equals(id) ? null : id;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
