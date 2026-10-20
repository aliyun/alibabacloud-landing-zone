package com.aliyun.autowonder.workitem;

import com.aliyun.autowonder.dispatch.DispatchDO;
import com.aliyun.autowonder.dispatch.DispatchRuntimeEventDO;
import com.aliyun.autowonder.dispatch.DispatchStatus;
import com.aliyun.autowonder.sdlc.SdlcStepDO;
import com.aliyun.autowonder.workitem.dto.StepTimingVO;

import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Per-dispatch elapsed step time. Queueing and other dispatches are never attributed to a step. */
public final class DispatchStepTimings {
    private static final Set<String> RUNNING = Set.of("DISPATCHED", "ACKED", "RUNNING",
            "PAUSING", "PAUSE_FAILED", "WAITING_FOR_PAUSE");

    private DispatchStepTimings() {}

    public static List<StepTimingVO> build(DispatchDO dispatch, List<DispatchRuntimeEventDO> events,
                                           List<SdlcStepDO> steps, Date now) {
        List<DispatchRuntimeEventDO> ownEvents = events.stream()
                .filter(event -> Objects.equals(dispatch.getId(), event.getDispatchId())).toList();
        boolean running = RUNNING.contains(Objects.toString(dispatch.getStatus(), ""));
        Date cutoff = now;
        if (!running) {
            cutoff = dispatch.getGmtModified();
            if (cutoff == null) {
                cutoff = ownEvents.stream().map(DispatchStepTimings::eventTime)
                        .filter(Objects::nonNull).max(Date::compareTo).orElse(null);
            }
        }
        if (cutoff != null && cutoff.after(now)) cutoff = now;
        RuntimeStepTimeline timeline = RuntimeStepTimeline.from(ownEvents, steps, cutoff);
        return steps.stream().map(step -> {
            StepTimingVO timing = new StepTimingVO();
            timing.setStepId(step.getId());
            timing.setName(step.getName());
            timing.setDurationMs(timeline.durationOf(step));
            String status = timeline.statusOf(step);
            DispatchRuntimeEventDO last = timeline.lastEventOf(step);
            if (last != null && last.getStepName() != null && !last.getStepName().isBlank()) {
                timing.setName(last.getStepName());
            }
            if (last != null && "step.reused".equals(last.getEventType())) status = "reused";
            if (last != null && "step.skipped".equals(last.getEventType())) status = "skipped";
            if ("active".equals(status) && !running) {
                status = DispatchStatus.PAUSED.equals(dispatch.getStatus()) ? "paused"
                        : DispatchStatus.CANCELED.equals(dispatch.getStatus()) ? "cancelled"
                        : DispatchStatus.FAILED.equals(dispatch.getStatus()) || DispatchStatus.TIMEOUT.equals(dispatch.getStatus())
                        ? "failed" : "interrupted";
            }
            timing.setStatus(status == null ? "pending" : status);
            return timing;
        }).toList();
    }

    private static Date eventTime(DispatchRuntimeEventDO event) {
        return event.getEventTime() != null ? event.getEventTime() : event.getGmtCreate();
    }
}
