package com.aliyun.autowonder.workitem;

import com.aliyun.autowonder.dispatch.DispatchRuntimeEventDO;
import com.aliyun.autowonder.sdlc.SdlcStepDO;
import com.aliyun.autowonder.util.MojibakeDetector;
import com.aliyun.autowonder.workitem.dto.SubStepVO;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 从 dispatch runtime 事件推导每个 SDLC 步骤的状态、子步骤与耗时。
 *
 * <p>eventsByOrder 的桶保持 DAO 的插入顺序（ORDER BY id ASC），lastEvent 依赖该顺序，
 * 任何排序都必须在副本上进行。
 */
class RuntimeStepTimeline {

    private final Map<Integer, List<DispatchRuntimeEventDO>> eventsByOrder;
    private final Integer latestOrder;
    private final String latestEventType;
    private final Map<Integer, Long> durationByOrder;

    private RuntimeStepTimeline(Map<Integer, List<DispatchRuntimeEventDO>> eventsByOrder,
                               Integer latestOrder, String latestEventType,
                               Map<Integer, Long> durationByOrder) {
        this.eventsByOrder = eventsByOrder;
        this.latestOrder = latestOrder;
        this.latestEventType = latestEventType;
        this.durationByOrder = durationByOrder;
    }

    static RuntimeStepTimeline from(List<DispatchRuntimeEventDO> events, List<SdlcStepDO> steps, Date now) {
        Map<Integer, SdlcStepDO> stepsByOrder = steps.stream()
                .filter(s -> s.getStepOrder() != null)
                .collect(Collectors.toMap(SdlcStepDO::getStepOrder, s -> s, (a, b) -> a, LinkedHashMap::new));
        Map<Long, Integer> orderByStepId = steps.stream()
                .filter(s -> s.getId() != null && s.getStepOrder() != null)
                .collect(Collectors.toMap(SdlcStepDO::getId, SdlcStepDO::getStepOrder, (a, b) -> a));
        Map<String, Integer> orderByName = steps.stream()
                .filter(s -> s.getName() != null && s.getStepOrder() != null)
                .collect(Collectors.toMap(s -> s.getName().trim(), SdlcStepDO::getStepOrder, (a, b) -> a));
        Map<String, Integer> orderByCode = steps.stream()
                .filter(s -> s.getCode() != null && !s.getCode().isBlank() && s.getStepOrder() != null)
                .collect(Collectors.toMap(s -> s.getCode().trim(), SdlcStepDO::getStepOrder, (a, b) -> a));

        Map<Integer, List<DispatchRuntimeEventDO>> byOrder = new LinkedHashMap<>();
        Integer latestOrder = null;
        String latestType = null;
        for (DispatchRuntimeEventDO event : events) {
            Integer order = resolveOrder(event, stepsByOrder, orderByStepId, orderByName, orderByCode);
            if (order == null) {
                continue;
            }
            byOrder.computeIfAbsent(order, ignored -> new ArrayList<>()).add(event);
            if (latestOrder == null || compareEvent(event, lastEvent(byOrder.get(latestOrder))) >= 0) {
                latestOrder = order;
                latestType = event.getEventType();
            }
        }
        return new RuntimeStepTimeline(byOrder, latestOrder, latestType, computeDurations(byOrder, now));
    }

    String statusOf(SdlcStepDO step) {
        if (latestOrder == null || step.getStepOrder() == null) {
            return null;
        }
        int order = step.getStepOrder();
        List<DispatchRuntimeEventDO> events = eventsByOrder.get(order);
        DispatchRuntimeEventDO last = lastEvent(events);
        if (last != null && "step.reused".equals(last.getEventType())) {
            return "done";
        }
        if (last != null && "step.stale".equals(last.getEventType())) {
            return "pending";
        }
        if (last != null && isFailureEvent(last)) {
            return "failed";
        }
        if (order < latestOrder) {
            return "done";
        }
        if (order > latestOrder) {
            return "pending";
        }
        if (isCompletionEvent(latestEventType)) {
            return "done";
        }
        return "active";
    }

    boolean isCurrent(SdlcStepDO step) {
        return latestOrder != null && Objects.equals(latestOrder, step.getStepOrder());
    }

    List<SubStepVO> subStepsOf(SdlcStepDO step, String stepStatus) {
        if (step.getStepOrder() == null) {
            return List.of();
        }
        List<DispatchRuntimeEventDO> events = eventsByOrder.get(step.getStepOrder());
        if (events == null || events.isEmpty()) {
            return List.of();
        }
        List<SubStepVO> result = new ArrayList<>();
        for (int i = 0; i < events.size(); i++) {
            DispatchRuntimeEventDO event = events.get(i);
            SubStepVO vo = new SubStepVO();
            vo.setName(labelOf(event));
            if (isFailureEvent(event)) {
                vo.setStatus("failed");
            } else if (i == events.size() - 1
                    && ("active".equals(stepStatus) || "paused".equals(stepStatus)
                    || "failed".equals(stepStatus))) {
                vo.setStatus(stepStatus);
            } else {
                vo.setStatus("done");
            }
            result.add(vo);
        }
        return result;
    }

    DispatchRuntimeEventDO lastEventOf(SdlcStepDO step) {
        if (step.getStepOrder() == null) {
            return null;
        }
        return lastEvent(eventsByOrder.get(step.getStepOrder()));
    }

    Long durationOf(SdlcStepDO step) {
        if (step == null || step.getStepOrder() == null) {
            return null;
        }
        return durationByOrder.get(step.getStepOrder());
    }

    /** event_time 可空（客户端上报），回退到服务端落库时间 gmt_create。 */
    private static Date at(DispatchRuntimeEventDO e) {
        return e.getEventTime() != null ? e.getEventTime() : e.getGmtCreate();
    }

    private static long positiveDelta(Date from, Date to) {
        if (from == null || to == null) {
            return 0L;
        }
        long delta = to.getTime() - from.getTime();
        return delta > 0 ? delta : 0L;
    }

    /**
     * 返回按到达顺序（id 升序，event time 兜底）排好的副本，供区间配对使用。
     * 绝不就地排序入参 —— 原桶的插入顺序被 lastEvent 依赖。
     * 以到达顺序为主：客户端时钟回拨时，terminal 的时间可能早于 started，
     * 按时间排序会让 terminal 排到 started 前面而无法配对；按到达顺序配对后由
     * positiveDelta 把负区间夹到 0。
     */
    private static List<DispatchRuntimeEventDO> sortedByTime(List<DispatchRuntimeEventDO> bucket) {
        List<DispatchRuntimeEventDO> copy = new ArrayList<>(bucket);
        copy.sort(Comparator
                .comparingLong((DispatchRuntimeEventDO e) -> e.getId() == null ? Long.MIN_VALUE : e.getId())
                .thenComparingLong(e -> {
                    Date at = at(e);
                    return at == null ? Long.MIN_VALUE : at.getTime();
                }));
        return copy;
    }

    private static final Set<String> TERMINAL_EVENT_TYPES = Set.of(
            "step.completed", "step.failed", "step.reused", "step.stale");

    /**
     * 一次遍历预计算每个 stepOrder 的耗时，避免 durationOf 被逐步骤调用时重扫事件流。
     *
     * <p>区间求和而非首末跨度：步骤被打回重跑时，两次执行之间夹着其它步骤的耗时。
     */
    private static Map<Integer, Long> computeDurations(
            Map<Integer, List<DispatchRuntimeEventDO>> eventsByOrder, Date now) {
        Map<Integer, Long> durations = new LinkedHashMap<>();
        for (Map.Entry<Integer, List<DispatchRuntimeEventDO>> entry : eventsByOrder.entrySet()) {
            long total = 0L;
            Date openStart = null;
            boolean sawStarted = false;
            for (DispatchRuntimeEventDO event : sortedByTime(entry.getValue())) {
                String type = event.getEventType();
                if ("step.started".equals(type)) {
                    sawStarted = true;
                    if (openStart == null) {
                        openStart = at(event);
                    }
                } else if (TERMINAL_EVENT_TYPES.contains(type) && openStart != null) {
                    total += positiveDelta(openStart, at(event));
                    openStart = null;
                }
            }
            if (openStart != null) {
                total += positiveDelta(openStart, now);
            }
            if (sawStarted) {
                durations.put(entry.getKey(), total);
            }
        }
        return durations;
    }

    private static Integer resolveOrder(DispatchRuntimeEventDO event, Map<Integer, SdlcStepDO> stepsByOrder,
                                        Map<Long, Integer> orderByStepId, Map<String, Integer> orderByName,
                                        Map<String, Integer> orderByCode) {
        if (event.getStepOrder() != null && stepsByOrder.containsKey(event.getStepOrder())) {
            return event.getStepOrder();
        }
        if (event.getStepId() != null && orderByStepId.containsKey(event.getStepId())) {
            return orderByStepId.get(event.getStepId());
        }
        if (event.getStepKey() != null) {
            Integer order = orderByCode.get(event.getStepKey().trim());
            if (order != null) {
                return order;
            }
        }
        if (event.getStepName() != null) {
            Integer order = orderByName.get(event.getStepName().trim());
            if (order != null) {
                return order;
            }
        }
        return null;
    }

    private static int compareEvent(DispatchRuntimeEventDO left, DispatchRuntimeEventDO right) {
        if (right == null) {
            return 1;
        }
        if (left.getId() != null && right.getId() != null) {
            return left.getId().compareTo(right.getId());
        }
        if (left.getGmtCreate() != null && right.getGmtCreate() != null) {
            return left.getGmtCreate().compareTo(right.getGmtCreate());
        }
        return 0;
    }

    private static DispatchRuntimeEventDO lastEvent(List<DispatchRuntimeEventDO> events) {
        if (events == null || events.isEmpty()) {
            return null;
        }
        return events.get(events.size() - 1);
    }

    private static boolean isCompletionEvent(String eventType) {
        return "step.completed".equals(eventType)
                || "step.completion_requested".equals(eventType)
                || "completion_requested".equals(eventType)
                || "dispatch.completed".equals(eventType);
    }

    private static boolean isFailureEvent(DispatchRuntimeEventDO event) {
        String type = event.getEventType();
        return event.getError() != null
                || "step.failed".equals(type)
                || "dispatch.failed".equals(type);
    }

    private static String labelOf(DispatchRuntimeEventDO event) {
        if (event.getMessage() != null && !event.getMessage().isBlank()
                && !MojibakeDetector.looksLikeMojibake(event.getMessage())) {
            return event.getMessage();
        }
        String eventLabel = runtimeEventLabel(event.getEventType());
        if (eventLabel != null) {
            return eventLabel;
        }
        if (event.getEventType() != null) {
            return event.getEventType();
        }
        return "运行进度";
    }

    private static String runtimeEventLabel(String eventType) {
        if (eventType == null) {
            return null;
        }
        if (eventType.startsWith("step.started")) {
            return "开始执行";
        }
        if (isCompletionEvent(eventType)) {
            return "请求完成";
        }
        if ("step.gate_started".equals(eventType)) {
            return "开始校验";
        }
        if ("step.gate_finished".equals(eventType)) {
            return "校验完成";
        }
        if ("step.fix_required".equals(eventType)) {
            return "需要修复";
        }
        if ("step.failed".equals(eventType) || "dispatch.failed".equals(eventType)) {
            return "执行失败";
        }
        return null;
    }
}
