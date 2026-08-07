package com.aliyun.autowonder.insights.participation;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;

import java.time.*;
import java.time.temporal.IsoFields;
import java.time.temporal.WeekFields;
import java.util.*;
import java.util.stream.Collectors;

public class HumanAgentParticipationCalculator {

    public enum Granularity { DAY, WEEK, MONTH }

    public static class ParticipationSummary {
        private final List<HumanAgentParticipationFact> eligibleFacts;
        private final long averageTotalSeconds;
        private final long averageHumanSeconds;
        private final long averageAgentSeconds;
        private final HumanAgentParticipationFact p90;
        private final List<HumanAgentParticipationFact> slowTail;
        private final List<TrendBucket> trend;

        public ParticipationSummary(List<HumanAgentParticipationFact> eligibleFacts,
                                    long averageTotalSeconds, long averageHumanSeconds,
                                    long averageAgentSeconds,
                                    HumanAgentParticipationFact p90,
                                    List<HumanAgentParticipationFact> slowTail,
                                    List<TrendBucket> trend) {
            this.eligibleFacts = eligibleFacts;
            this.averageTotalSeconds = averageTotalSeconds;
            this.averageHumanSeconds = averageHumanSeconds;
            this.averageAgentSeconds = averageAgentSeconds;
            this.p90 = p90;
            this.slowTail = slowTail;
            this.trend = trend;
        }

        public List<HumanAgentParticipationFact> eligibleFacts() { return eligibleFacts; }
        public long averageTotalSeconds() { return averageTotalSeconds; }
        public long averageHumanSeconds() { return averageHumanSeconds; }
        public long averageAgentSeconds() { return averageAgentSeconds; }
        public HumanAgentParticipationFact p90() { return p90; }
        public List<HumanAgentParticipationFact> slowTail() { return slowTail; }
        public List<TrendBucket> trend() { return trend; }
    }

    public static class TrendBucket {
        private final String label;
        private final long averageTotalSeconds;
        private final long averageHumanSeconds;
        private final long averageAgentSeconds;

        public TrendBucket(String label, long averageTotalSeconds,
                           long averageHumanSeconds, long averageAgentSeconds) {
            this.label = label;
            this.averageTotalSeconds = averageTotalSeconds;
            this.averageHumanSeconds = averageHumanSeconds;
            this.averageAgentSeconds = averageAgentSeconds;
        }

        public String label() { return label; }
        public long averageTotalSeconds() { return averageTotalSeconds; }
        public long averageHumanSeconds() { return averageHumanSeconds; }
        public long averageAgentSeconds() { return averageAgentSeconds; }
    }

    public List<HumanAgentParticipationFact> reconstruct(
            List<HumanAgentParticipationRawEventRow> rows, Instant cutoff) {
        Map<Long, List<HumanAgentParticipationRawEventRow>> grouped = rows.stream()
                .collect(Collectors.groupingBy(HumanAgentParticipationRawEventRow::getWorkitemId,
                        LinkedHashMap::new, Collectors.toList()));

        List<HumanAgentParticipationFact> facts = new ArrayList<>();
        for (Map.Entry<Long, List<HumanAgentParticipationRawEventRow>> entry : grouped.entrySet()) {
            HumanAgentParticipationFact fact = reconstructOne(entry.getValue(), cutoff);
            if (fact != null) {
                facts.add(fact);
            }
        }
        return facts;
    }

    private HumanAgentParticipationFact reconstructOne(
            List<HumanAgentParticipationRawEventRow> events, Instant cutoff) {
        if (events.isEmpty()) return null;

        HumanAgentParticipationRawEventRow first = events.get(0);
        if (!"CREATE".equals(first.getEventType())) return null;

        Instant createdAt = first.getEventAt().toInstant();
        String owner = "HUMAN";
        Instant cursor = createdAt;
        long agentSeconds = 0;
        long humanSeconds = 0;
        boolean foundTerminal = false;
        Instant terminalAt = null;

        for (int i = 1; i < events.size(); i++) {
            HumanAgentParticipationRawEventRow e = events.get(i);

            if ("ASSIGN".equals(e.getEventType())) {
                String toType = parseAssignmentType(e.getDetailJson());
                if (toType == null) {
                    toType = normalizeAssignmentType(e.getInferredToType());
                }
                if (toType == null) continue;
                Instant eventAt = e.getEventAt().toInstant();
                if (eventAt.isBefore(cursor)) return null;
                long intervalSeconds = Duration.between(cursor, eventAt).getSeconds();
                if ("AGENT".equals(owner)) {
                    agentSeconds += intervalSeconds;
                } else {
                    humanSeconds += intervalSeconds;
                }
                owner = toType;
                cursor = eventAt;
            } else if ("STATUS_CHANGE".equals(e.getEventType()) && e.isTerminal()) {
                if (foundTerminal) continue;
                Instant eventAt = e.getEventAt().toInstant();
                if (eventAt.isAfter(cutoff)) return null;
                if (eventAt.isBefore(cursor)) return null;
                long intervalSeconds = Duration.between(cursor, eventAt).getSeconds();
                if ("AGENT".equals(owner)) {
                    agentSeconds += intervalSeconds;
                } else {
                    humanSeconds += intervalSeconds;
                }
                foundTerminal = true;
                terminalAt = eventAt;
                break;
            }
        }

        if (!foundTerminal) return null;

        long totalSeconds = Duration.between(createdAt, terminalAt).getSeconds();
        return new HumanAgentParticipationFact(
                first.getWorkitemId(), first.getTitle(), terminalAt,
                totalSeconds, humanSeconds, agentSeconds);
    }

    static String parseAssignmentType(String detailJson) {
        if (detailJson == null || detailJson.isBlank()) return null;
        try {
            JSONObject detail = JSON.parseObject(detailJson);
            String toType = detail == null ? null : detail.getString("toType");
            return ("HUMAN".equals(toType) || "AGENT".equals(toType)) ? toType : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    static String normalizeAssignmentType(String type) {
        if (type == null || type.isBlank()) return null;
        String normalized = type.trim().toUpperCase(Locale.ROOT);
        return ("HUMAN".equals(normalized) || "AGENT".equals(normalized)) ? normalized : null;
    }

    public ParticipationSummary summarize(List<HumanAgentParticipationFact> allFacts,
                                           LocalDate start, LocalDate end,
                                           Granularity granularity, ZoneId zone) {
        List<HumanAgentParticipationFact> inRange = allFacts.stream()
                .filter(f -> {
                    LocalDate completedDate = f.completedAt().atZone(zone).toLocalDate();
                    return !completedDate.isBefore(start) && !completedDate.isAfter(end);
                })
                .collect(Collectors.toList());

        if (inRange.isEmpty()) {
            return new ParticipationSummary(inRange, 0, 0, 0, null, Collections.emptyList(),
                    Collections.emptyList());
        }

        long sumTotal = 0, sumHuman = 0, sumAgent = 0;
        for (HumanAgentParticipationFact f : inRange) {
            sumTotal += f.totalDurationSeconds();
            sumHuman += f.humanDurationSeconds();
            sumAgent += f.agentDurationSeconds();
        }
        int n = inRange.size();
        long avgTotal = sumTotal / n;
        long avgHuman = sumHuman / n;
        long avgAgent = sumAgent / n;

        List<HumanAgentParticipationFact> sortedAsc = inRange.stream()
                .sorted(Comparator.comparingLong(HumanAgentParticipationFact::totalDurationSeconds)
                        .thenComparing(HumanAgentParticipationFact::completedAt)
                        .thenComparingLong(HumanAgentParticipationFact::workitemId))
                .collect(Collectors.toList());

        int p90Rank = (int) Math.ceil(n * 0.90d);
        HumanAgentParticipationFact p90 = sortedAsc.get(p90Rank - 1);

        int tailSize = Math.max(1, (int) Math.ceil(n * 0.10d));
        List<HumanAgentParticipationFact> sortedDesc = inRange.stream()
                .sorted(Comparator.comparingLong(HumanAgentParticipationFact::totalDurationSeconds).reversed()
                        .thenComparing(HumanAgentParticipationFact::completedAt, Comparator.reverseOrder())
                        .thenComparing(Comparator.comparingLong(HumanAgentParticipationFact::workitemId).reversed()))
                .limit(tailSize)
                .collect(Collectors.toList());

        List<TrendBucket> trend = buildTrend(inRange, granularity, zone);

        return new ParticipationSummary(inRange, avgTotal, avgHuman, avgAgent, p90, sortedDesc, trend);
    }

    private List<TrendBucket> buildTrend(List<HumanAgentParticipationFact> facts,
                                          Granularity granularity, ZoneId zone) {
        Map<String, List<HumanAgentParticipationFact>> buckets = new TreeMap<>();
        for (HumanAgentParticipationFact f : facts) {
            String label = bucketLabel(f.completedAt(), granularity, zone);
            buckets.computeIfAbsent(label, k -> new ArrayList<>()).add(f);
        }

        List<TrendBucket> result = new ArrayList<>();
        for (Map.Entry<String, List<HumanAgentParticipationFact>> entry : buckets.entrySet()) {
            List<HumanAgentParticipationFact> bucketFacts = entry.getValue();
            int count = bucketFacts.size();
            long bTotal = 0, bHuman = 0, bAgent = 0;
            for (HumanAgentParticipationFact f : bucketFacts) {
                bTotal += f.totalDurationSeconds();
                bHuman += f.humanDurationSeconds();
                bAgent += f.agentDurationSeconds();
            }
            long bAvgTotal = bTotal / count;
            long bAvgHuman = bHuman / count;
            long bAvgAgent = bAgent / count;
            result.add(new TrendBucket(entry.getKey(), bAvgTotal, bAvgHuman, bAvgAgent));
        }
        return result;
    }

    static String bucketLabel(Instant instant, Granularity granularity, ZoneId zone) {
        LocalDate date = instant.atZone(zone).toLocalDate();
        switch (granularity) {
            case DAY:
                return date.toString();
            case WEEK:
                LocalDate monday = date.with(WeekFields.ISO.dayOfWeek(), 1);
                return monday.toString();
            case MONTH:
                return date.withDayOfMonth(1).toString();
            default:
                throw new IllegalArgumentException("Unknown granularity: " + granularity);
        }
    }
}
