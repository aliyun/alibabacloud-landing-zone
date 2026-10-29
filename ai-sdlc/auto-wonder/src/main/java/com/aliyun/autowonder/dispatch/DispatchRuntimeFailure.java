package com.aliyun.autowonder.dispatch;

import java.util.List;

/** Shared runtime-event failure snapshot used by delivery-progress and pause write paths. */
public final class DispatchRuntimeFailure {

    private DispatchRuntimeFailure() {
    }

    public static boolean isFailure(DispatchRuntimeEventDO event) {
        if (event == null) {
            return false;
        }
        String type = event.getEventType();
        return event.getError() != null
                || "step.failed".equals(type)
                || "dispatch.failed".equals(type)
                || "runtime.failed".equals(type)
                || "task.failed".equals(type);
    }

    public static DispatchRuntimeEventDO latest(List<DispatchRuntimeEventDO> events) {
        if (events == null || events.isEmpty()) {
            return null;
        }
        return events.stream().filter(java.util.Objects::nonNull)
                .max(DispatchRuntimeFailure::compare)
                .orElse(null);
    }

    public static String message(DispatchRuntimeEventDO event) {
        if (event == null) {
            return null;
        }
        if (event.getError() != null && !event.getError().isBlank()) {
            return event.getError();
        }
        if (event.getMessage() != null && !event.getMessage().isBlank()) {
            return event.getMessage();
        }
        return event.getEventType();
    }

    private static int compare(DispatchRuntimeEventDO left, DispatchRuntimeEventDO right) {
        if (left.getId() != null && right.getId() != null) return left.getId().compareTo(right.getId());
        if (left.getGmtCreate() != null && right.getGmtCreate() != null) {
            return left.getGmtCreate().compareTo(right.getGmtCreate());
        }
        if (left.getEventTime() != null && right.getEventTime() != null) {
            return left.getEventTime().compareTo(right.getEventTime());
        }
        return 0;
    }
}
