package com.aliyun.autowonder.insights.participation;

import com.aliyun.autowonder.insights.participation.HumanAgentParticipationSnapshotStore.ParsedSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Single read path for completion-class metrics: T+1 snapshot facts up to yesterday
 * plus a live tail for today. Pipeline health and human–agent participation must both
 * read through this layer so their averages stay identical for the same window.
 */
@Component
public class CompletionFactQuery {

    private static final Logger log = LoggerFactory.getLogger(CompletionFactQuery.class);
    static final long REBUILD_TRIGGER_INTERVAL_MS = TimeUnit.HOURS.toMillis(1);

    private final HumanAgentParticipationRefreshService refreshService;
    private final HumanAgentParticipationProperties properties;
    private final Map<Long, Long> rebuildTriggeredAt = new ConcurrentHashMap<>();

    public CompletionFactQuery(HumanAgentParticipationRefreshService refreshService,
                               HumanAgentParticipationProperties properties) {
        this.refreshService = refreshService;
        this.properties = properties;
    }

    public Optional<ParsedSnapshot> ensureSnapshot(long tenantId) {
        Optional<ParsedSnapshot> snapshot = refreshService.read(tenantId);
        if (snapshot.isEmpty()) {
            refreshService.requestRefresh(tenantId);
            refreshService.waitForRefresh(tenantId, properties.getCacheMissWaitMs());
            snapshot = refreshService.read(tenantId);
        }
        return snapshot;
    }

    public List<HumanAgentParticipationFact> range(long tenantId, LocalDate startInclusive,
                                                   LocalDate endInclusive) {
        return range(tenantId, startInclusive, endInclusive, null);
    }

    public List<HumanAgentParticipationFact> range(long tenantId, LocalDate startInclusive,
                                                   LocalDate endInclusive, String workType) {
        if (startInclusive == null || endInclusive == null || startInclusive.isAfter(endInclusive)) {
            return Collections.emptyList();
        }
        return range(ensureSnapshot(tenantId), tenantId, startInclusive, endInclusive, workType);
    }

    public List<HumanAgentParticipationFact> range(Optional<ParsedSnapshot> snapshot, long tenantId,
                                                   LocalDate startInclusive, LocalDate endInclusive) {
        return range(snapshot, tenantId, startInclusive, endInclusive, null);
    }

    public List<HumanAgentParticipationFact> range(Optional<ParsedSnapshot> snapshot, long tenantId,
                                                   LocalDate startInclusive, LocalDate endInclusive,
                                                   String workType) {
        if (startInclusive == null || endInclusive == null || startInclusive.isAfter(endInclusive)) {
            return Collections.emptyList();
        }
        ZoneId zone = ZoneId.of(properties.getTimezone());
        LocalDate today = LocalDate.now(zone);
        Map<Long, HumanAgentParticipationFact> merged = new LinkedHashMap<>();
        snapshot.ifPresent(s -> {
            if (s.items() == null) return;
            for (HumanAgentParticipationFact fact : s.items()) {
                addIfInRange(merged, fact, startInclusive, endInclusive, zone);
            }
        });
        if (!endInclusive.isBefore(today)) {
            List<HumanAgentParticipationFact> tail = refreshService.todayFacts(tenantId);
            if (tail != null) {
                for (HumanAgentParticipationFact fact : tail) {
                    addIfInRange(merged, fact, startInclusive, endInclusive, zone);
                }
            }
        }
        List<HumanAgentParticipationFact> facts = new ArrayList<>(merged.values());
        facts.sort(Comparator.comparing(HumanAgentParticipationFact::completedAt)
                .thenComparingLong(HumanAgentParticipationFact::workitemId));
        String filter = normalizeWorkType(workType);
        if (filter == null) {
            return facts;
        }
        List<HumanAgentParticipationFact> matching = new ArrayList<>();
        for (HumanAgentParticipationFact fact : facts) {
            if (matchesWorkType(fact, filter)) {
                matching.add(fact);
            }
        }
        triggerRebuildWhenWorkTypeMissing(tenantId, facts, filter);
        return matching;
    }

    /**
     * 旧快照事实缺少 workType 时，兼容逻辑会把每条事实都当作匹配所有类型，筛选形同虚设。
     * 此时记 WARN 并按租户限频触发一次异步重建，让快照尽快补上类型；本次请求仍按兼容语义返回。
     */
    private void triggerRebuildWhenWorkTypeMissing(long tenantId,
                                                   List<HumanAgentParticipationFact> facts,
                                                   String workType) {
        if (facts.isEmpty()) {
            return;
        }
        for (HumanAgentParticipationFact fact : facts) {
            if (hasWorkType(fact)) {
                return;
            }
        }
        log.warn("Participation facts all lack workType, type filter has no effect tenantId={} workType={}",
                tenantId, workType);
        long now = System.currentTimeMillis();
        Long lastTriggeredAt = rebuildTriggeredAt.get(tenantId);
        if (lastTriggeredAt != null && now - lastTriggeredAt < REBUILD_TRIGGER_INTERVAL_MS) {
            return;
        }
        rebuildTriggeredAt.put(tenantId, now);
        refreshService.requestRefresh(tenantId);
    }

    /** 只认已知工单类型；缺省、ALL 和无法识别的值都按不过滤处理。 */
    private static String normalizeWorkType(String workType) {
        if (workType == null) return null;
        String normalized = workType.trim().toUpperCase(Locale.ROOT);
        return ("REQ".equals(normalized) || "BUG".equals(normalized) || "TASK".equals(normalized))
                ? normalized : null;
    }

    /** 旧快照缺失 workType 的事实没有类型信息，按匹配所有类型处理。 */
    private static boolean matchesWorkType(HumanAgentParticipationFact fact, String workType) {
        return !hasWorkType(fact) || workType.equalsIgnoreCase(fact.workType().trim());
    }

    private static boolean hasWorkType(HumanAgentParticipationFact fact) {
        String factWorkType = fact.workType();
        return factWorkType != null && !factWorkType.isBlank();
    }

    private static void addIfInRange(Map<Long, HumanAgentParticipationFact> merged,
                                     HumanAgentParticipationFact fact,
                                     LocalDate startInclusive, LocalDate endInclusive, ZoneId zone) {
        LocalDate completedDate = fact.completedAt().atZone(zone).toLocalDate();
        if (completedDate.isBefore(startInclusive) || completedDate.isAfter(endInclusive)) return;
        // 同一工单在快照与尾重叠时保留快照事实，历史值不随实时重建变化。
        merged.putIfAbsent(fact.workitemId(), fact);
    }

    public Integer averageMinutes(long tenantId, LocalDate startInclusive, LocalDate endInclusive) {
        return HumanAgentParticipationCalculator.averageMinutes(
                eligible(range(tenantId, startInclusive, endInclusive)));
    }

    public boolean includesRealtimeTail(LocalDate endInclusive) {
        ZoneId zone = ZoneId.of(properties.getTimezone());
        return endInclusive != null && !endInclusive.isBefore(LocalDate.now(zone));
    }

    private static List<HumanAgentParticipationFact> eligible(List<HumanAgentParticipationFact> facts) {
        List<HumanAgentParticipationFact> eligible = new ArrayList<>();
        for (HumanAgentParticipationFact fact : facts) {
            if (fact.exclusionReason() == null) eligible.add(fact);
        }
        return eligible;
    }
}
