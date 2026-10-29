package com.aliyun.autowonder.insights;

import com.aliyun.autowonder.common.error.KeyedIllegalArgumentException;
import com.aliyun.autowonder.dispatch.DispatchDO;
import com.aliyun.autowonder.dispatch.DispatchRuntimeEventDO;
import com.aliyun.autowonder.insights.dto.StageDurationVO;
import com.aliyun.autowonder.sdlc.SdlcStepDO;
import com.aliyun.autowonder.workitem.DispatchStepTimings;
import lombok.Getter;
import lombok.Setter;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class StageDurationService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    // shortcut: aggregate at most 5000 executions in memory; use persisted facts when larger ranges are needed.
    private static final int MAX_EXECUTIONS = 5000;
    private static final int EVENT_BATCH_SIZE = 100;
    private final StageDurationDao dao;

    public StageDurationService(StageDurationDao dao) { this.dao = dao; }

    @Getter
    @Setter
    public static class Execution extends DispatchDO {
        private String title;
        private Long sdlcId;
        private String sdlcName;
        private String agentName;
    }

    @Getter
    @Setter
    public static class Event extends DispatchRuntimeEventDO {
        private String fallbackStepName;
    }

    public StageDurationVO get(long tenantId, LocalDate start, LocalDate end, Long squadId, Long sdlcId) {
        if (start == null || end == null || start.isAfter(end) || end.isAfter(LocalDate.now(ZONE))
                || ChronoUnit.DAYS.between(start, end) >= 366) {
            throw new KeyedIllegalArgumentException("insights.invalid_date_range", null,
                    "Invalid stage duration range: " + start + ".." + end);
        }
        if ((squadId != null && squadId <= 0) || (sdlcId != null && sdlcId <= 0)) {
            throw new KeyedIllegalArgumentException("error.param_invalid", null, "Stage duration filters must be positive ids");
        }
        Date now = new Date();
        List<Execution> executions = dao.listExecutions(tenantId,
                Date.from(start.atStartOfDay(ZONE).toInstant()), Date.from(end.plusDays(1).atStartOfDay(ZONE).toInstant()),
                squadId, sdlcId, MAX_EXECUTIONS + 1);
        if (executions.size() > MAX_EXECUTIONS) {
            throw new KeyedIllegalArgumentException("insights.stage_duration_range_too_large", new Object[]{MAX_EXECUTIONS},
                    "Stage duration query exceeds " + MAX_EXECUTIONS + " executions");
        }
        Map<Long, List<Event>> events = new HashMap<>();
        List<Long> dispatchIds = executions.stream().map(Execution::getId).toList();
        // Bound each index lookup instead of sending thousands of IDs in one range query.
        for (int offset = 0; offset < dispatchIds.size(); offset += EVENT_BATCH_SIZE) {
            for (Event event : dao.listEvents(tenantId,
                    dispatchIds.subList(offset, Math.min(offset + EVENT_BATCH_SIZE, dispatchIds.size())))) {
                events.computeIfAbsent(event.getDispatchId(), ignored -> new ArrayList<>()).add(event);
            }
        }
        Map<String, StageDurationVO.Stage> stages = new LinkedHashMap<>();
        Map<String, List<StageDurationVO.Sample>> samples = new HashMap<>();
        int measured = 0;
        for (Execution execution : executions) {
            if (execution.getSdlcId() == null) continue;
            List<Event> ownEvents = events.getOrDefault(execution.getId(), List.of());
            Map<String, Set<String>> aliases = new HashMap<>();
            for (Event event : ownEvents) {
                String key = identity(event);
                if (key == null) continue;
                if (event.getStepId() != null && event.getStepKey() != null && !event.getStepKey().isBlank())
                    aliases.computeIfAbsent("key:" + event.getStepKey(), ignored -> new HashSet<>()).add(key);
                if ((event.getStepId() != null || event.getStepKey() != null) && event.getStepOrder() != null)
                    aliases.computeIfAbsent("order:" + event.getStepOrder(), ignored -> new HashSet<>()).add(key);
            }
            Map<String, SdlcStepDO> definitions = new LinkedHashMap<>();
            Map<String, Integer> recordedOrders = new HashMap<>();
            List<DispatchRuntimeEventDO> normalized = new ArrayList<>();
            for (Event event : ownEvents) {
                String identity = identity(event);
                if (identity == null) continue;
                // Older events may have only an order/key. Resolve it only when the recorded identity is unique.
                Set<String> candidates = aliases.get(identity);
                if (candidates != null && candidates.size() == 1) identity = candidates.iterator().next();
                SdlcStepDO step = definitions.computeIfAbsent(identity, key -> {
                    SdlcStepDO value = new SdlcStepDO();
                    value.setId(event.getStepId());
                    value.setStepOrder(definitions.size() + 1);
                    return value;
                });
                if (event.getStepId() != null) step.setId(event.getStepId());
                if (event.getStepName() != null && !event.getStepName().isBlank()) step.setName(event.getStepName());
                else if (step.getName() == null) step.setName(event.getFallbackStepName());
                if (event.getStepOrder() != null) recordedOrders.put(identity, event.getStepOrder());
                // Normalize only the projection: historical step ids/names survive edits and deleted definitions.
                DispatchRuntimeEventDO copy = new DispatchRuntimeEventDO();
                copy.setId(event.getId());
                copy.setDispatchId(execution.getId());
                copy.setStepOrder(step.getStepOrder());
                copy.setStepName(event.getStepName());
                copy.setEventType(event.getEventType());
                copy.setEventTime(event.getEventTime());
                copy.setGmtCreate(event.getGmtCreate());
                normalized.add(copy);
            }
            var timings = DispatchStepTimings.build(execution, normalized, new ArrayList<>(definitions.values()), now);
            int index = 0;
            boolean hasMeasurement = false;
            for (var entry : definitions.entrySet()) {
                var timing = timings.get(index++);
                String key = execution.getSdlcId() + ":" + entry.getKey();
                StageDurationVO.Stage stage = stages.computeIfAbsent(key, ignored -> {
                    StageDurationVO.Stage value = new StageDurationVO.Stage();
                    value.setKey(key);
                    value.setSdlcId(execution.getSdlcId());
                    value.setSdlcName(execution.getSdlcName());
                    value.setStepId(entry.getValue().getId());
                    value.setStepOrder(recordedOrders.get(entry.getKey()));
                    value.setName(timing.getName());
                    return value;
                });
                StageDurationVO.Sample sample = new StageDurationVO.Sample();
                sample.setDispatchId(execution.getId());
                sample.setWorkitemId(execution.getWorkitemId());
                sample.setTitle(execution.getTitle());
                sample.setAgentName(execution.getAgentName());
                sample.setCreatedAt(execution.getGmtCreate().toInstant().toString());
                sample.setStatus(timing.getStatus());
                sample.setDurationMs(timing.getDurationMs());
                samples.computeIfAbsent(key, ignored -> new ArrayList<>()).add(sample);
                stage.setSampleCount(stage.getSampleCount() + 1);
                String status = timing.getStatus();
                if ("reused".equals(status)) stage.setReusedCount(stage.getReusedCount() + 1);
                else if ("skipped".equals(status)) stage.setSkippedCount(stage.getSkippedCount() + 1);
                else {
                    if ("done".equals(status)) stage.setCompletedCount(stage.getCompletedCount() + 1);
                    else if ("active".equals(status)) stage.setActiveCount(stage.getActiveCount() + 1);
                    else stage.setIncompleteCount(stage.getIncompleteCount() + 1);
                    if (timing.getDurationMs() == null) stage.setMissingDurationCount(stage.getMissingDurationCount() + 1);
                }
                if (timing.getDurationMs() != null) {
                    stage.setTotalDurationMs(stage.getTotalDurationMs() + timing.getDurationMs());
                    hasMeasurement = true;
                }
            }
            if (hasMeasurement) measured++;
        }
        for (StageDurationVO.Stage stage : stages.values()) {
            List<StageDurationVO.Sample> all = samples.get(stage.getKey());
            List<Long> completed = all.stream().filter(s -> "done".equals(s.getStatus()) && s.getDurationMs() != null)
                    .map(StageDurationVO.Sample::getDurationMs).sorted().toList();
            if (!completed.isEmpty()) {
                stage.setAverageDurationMs(Math.round(completed.stream().mapToLong(Long::longValue).average().orElseThrow()));
                int size = completed.size();
                stage.setMedianDurationMs(size % 2 == 1 ? completed.get(size / 2)
                        : Math.round((completed.get(size / 2 - 1) + completed.get(size / 2)) / 2.0));
                stage.setP90DurationMs(completed.get((int) Math.ceil(size * .9) - 1));
            }
            stage.setSlowest(all.stream().sorted(Comparator.comparing(StageDurationVO.Sample::getDurationMs,
                    Comparator.nullsLast(Comparator.reverseOrder())).thenComparing(StageDurationVO.Sample::getDispatchId))
                    .limit(10).toList());
        }
        StageDurationVO report = new StageDurationVO();
        report.setStartDate(start.toString());
        report.setEndDate(end.toString());
        report.setTimezone(ZONE.getId());
        report.setCalculatedAt(now.toInstant().toString());
        report.setExecutionCount(executions.size());
        report.setWorkitemCount(executions.stream().map(Execution::getWorkitemId).distinct().count());
        report.setMeasuredExecutionCount(measured);
        report.setMissingDurationCount(stages.values().stream().mapToInt(StageDurationVO.Stage::getMissingDurationCount).sum());
        report.setTotalDurationMs(stages.values().stream().mapToLong(StageDurationVO.Stage::getTotalDurationMs).sum());
        report.setStages(stages.values().stream().sorted(Comparator.comparingLong(StageDurationVO.Stage::getTotalDurationMs).reversed()
                .thenComparing(StageDurationVO.Stage::getKey)).toList());
        return report;
    }

    private static String identity(DispatchRuntimeEventDO event) {
        if (event.getStepId() != null) return "id:" + event.getStepId();
        if (event.getStepKey() != null && !event.getStepKey().isBlank()) return "key:" + event.getStepKey();
        if (event.getStepOrder() != null) return "order:" + event.getStepOrder();
        if (event.getStepName() != null && !event.getStepName().isBlank()) return "name:" + event.getStepName();
        return null;
    }
}
