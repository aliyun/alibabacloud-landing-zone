package com.aliyun.autowonder.dispatch.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * Browser-visible live activity contract for one dispatch. Every field is derived from persisted
 * {@code dispatch_runtime_event} rows through an allowlist, so no raw runtime log is ever exposed.
 */
@Getter
@Setter
public class DispatchLiveActivityVO {

    private String schemaVersion = "1";
    private Long dispatchId;
    private Long agentId;
    private Long workitemId;
    private String sourceType;
    private Integer attempt;
    private String dispatchStatus;
    /** False when {@code afterSeq} already covers every persisted event; {@code actions} is then empty. */
    private boolean changed = true;
    private Long lastSeq;
    /** Event time of the newest displayable action, ISO-8601; null when the runtime has not reported yet. */
    private String lastUpdatedAt;
    /** Newest action that is still RUNNING; null when the agent finished or failed its last action. */
    private Action currentAction;
    /** Newest actions first, already windowed to the requested limit. */
    private List<Action> actions = new ArrayList<>();
    /**
     * Displayable actions in this response before windowing. A full read reports the whole dispatch;
     * a delta read ({@code afterSeq} set) reports only the newly persisted actions.
     */
    private int totalActions;
    /** True when the window dropped older actions; the client must page or reload to see them. */
    private boolean truncated;
    /** True when a full read found nothing displayable yet, including old runtimes without events. */
    private boolean awaitingRuntime;

    @Getter
    @Setter
    public static class Action {
        private String eventId;
        private Long seq;
        /** ISO-8601 event time reported by the runtime. */
        private String eventTime;
        private String eventType;
        /** Stable display category, e.g. {@code SDLC_STEP}, {@code FILE_EDIT}, {@code COMMAND}. */
        private String actionType;
        /** Human readable, sanitized and length bounded summary. */
        private String summary;
        /** {@code RUNNING}, {@code COMPLETED}, {@code FAILED}, {@code PAUSED}, {@code RESUMED}, {@code CANCELLED} or {@code INFO}. */
        private String status;
        private Long stepId;
        private String stepKey;
        private String stepName;
        private Long agentId;
        private Long dispatchId;
        private Integer attempt;
    }
}
