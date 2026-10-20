package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.json.JSON;
import com.aliyun.autowonder.json.JSONObject;
import com.aliyun.autowonder.guidance.GuidanceService;
import com.aliyun.autowonder.guidance.InteractionWorkflowService;
import com.aliyun.autowonder.scheduledtask.compat.ScheduledTaskCapabilityGuard;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** The single result acknowledgement boundary: terminal replies always have a committed decision. */
@Service
public class DispatchResultCoordinator {
    private final DispatchResultStore store;
    private final DispatchDao dao;
    private final DispatchService dispatches;
    private final DispatchCheckpointService checkpoints;
    private final DispatchPauseService pauses;
    private final DispatchRecoveryService recovery;
    private final HandoffService handoffs;
    private final GuidanceService guidance;
    private final InteractionWorkflowService workflows;
    private final ScheduledTaskCapabilityGuard scheduled;

    public DispatchResultCoordinator(DispatchResultStore store, DispatchDao dao, DispatchService dispatches,
            DispatchCheckpointService checkpoints, DispatchPauseService pauses, DispatchRecoveryService recovery,
            HandoffService handoffs, GuidanceService guidance, InteractionWorkflowService workflows,
            ScheduledTaskCapabilityGuard scheduled) {
        this.store = store; this.dao = dao; this.dispatches = dispatches; this.checkpoints = checkpoints;
        this.pauses = pauses; this.recovery = recovery; this.handoffs = handoffs;
        this.guidance = guidance; this.workflows = workflows; this.scheduled = scheduled;
    }

    public Outcome handle(long tenantId, long executorId, JSONObject input) {
        boolean legacy = isLegacyResult(input);
        if (input.getLongValue("dispatchId") <= 0 || (!legacy
                && (!validResultId(input.getString("resultId"))
                    || input.getIntValue("resultProtocolVersion") != 2))) {
            return retry(input, "INVALID_RESULT_IDENTITY");
        }
        JSONObject decisionInput = legacy ? withLegacyIdentity(tenantId, executorId, input) : input;
        try {
            store.requireReady();
            String hash = payloadHash(decisionInput);
            DispatchResultStore.Decision previous = store.find(tenantId, decisionInput.getLongValue("dispatchId"), decisionInput.getString("resultId"));
            if (previous != null) return replay(input, executorId, hash, previous);
            DispatchDO subject = dao.findById(decisionInput.getLongValue("dispatchId"));
            if (subject == null || !Objects.equals(subject.getTenantId(), tenantId))
                return retry(input, "RESULT_OWNER_MISMATCH");
            try (ResultCommitActions actions = ResultCommitActions.open()) {
                Outcome outcome = store.transaction(() -> decide(tenantId, executorId, decisionInput, hash, subject, legacy));
                actions.committed();
                return outcome;
            }
        } catch (RuntimeException failure) {
            LoggerFactory.getLogger(getClass()).warn("result transaction retryable dispatchId={} resultId={}",
                    input.getLongValue("dispatchId"), decisionInput.getString("resultId"), failure);
            return retry(input, "RESULT_PROCESSING_UNAVAILABLE");
        }
    }

    private Outcome decide(long tenantId, long executorId, JSONObject input, String hash, DispatchDO subject, boolean legacy) {
        long id = input.getLongValue("dispatchId");
        String resultId = input.getString("resultId");
        if (subject.executionSourceType() == ExecutionSourceType.SCHEDULED_TASK_RUN) scheduled.requireAvailable("websocket");
        store.lockSubject(subject);
        store.lockDispatch(tenantId, id);
        DispatchResultStore.Decision previous = store.findLocked(tenantId, id, resultId);
        if (previous != null) return replay(input, executorId, hash, previous);
        DispatchDO row = dao.findById(id);
        if (!Objects.equals(row.getExecutorId(), executorId))
            return persist(tenantId, executorId, input, hash, "REJECTED", "STALE_EXECUTOR", null);
        // Cancellation must resolve before acknowledging the runtime's stopped execution.
        if (recovery.cancelRequested(tenantId, id)) {
            if (!recovery.onStopped(tenantId, executorId, id)) return retry(input, "STOP_NOT_DURABLE");
            return persist(tenantId, executorId, input, hash, "REJECTED", "DISPATCH_CANCELED", null);
        }
        if (DispatchStatus.isTerminal(row.getStatus())) {
            return persist(tenantId, executorId, input, hash, "REJECTED", "DISPATCH_ALREADY_RESOLVED", null);
        }
        // A released execution stays resumable. A delayed result must not turn its pause
        // into a protocol failure or apply an old handoff over the current human owner.
        if (DispatchStatus.PAUSED.equals(row.getStatus())) {
            return persist(tenantId, executorId, input, hash, "REJECTED", "DISPATCH_PAUSED", null);
        }
        if (recovery.fenced(row)) return rejectActive(tenantId, executorId, input, hash, "DISPATCH_FENCED");
        boolean success = Boolean.TRUE.equals(input.getBoolean("success"));
        // Legacy uploads have no completion pin; do not require a V2-only receipt from them.
        if (success && !legacy && !checkpoints.matchesCompletionReceipt(tenantId, id, resultId,
                input.getLongValue("checkpointSeq"), input.getString("checkpointSha256"))) {
            return rejectActive(tenantId, executorId, input, hash, "COMPLETION_CHECKPOINT_INVALID");
        }
        if (success) {
            DispatchPauseService.CompletionDisposition paused = pauses.onCompletedWhilePausing(
                    tenantId, executorId, id, input.getLongValue("checkpointSeq"), input.getString("checkpointSha256"));
            if (paused == DispatchPauseService.CompletionDisposition.PAUSED) {
                guidance.requeueDeliveredForDispatch(tenantId, id);
                workflows.onPaused(tenantId, id);
                return persist(tenantId, executorId, input, hash, "ACCEPTED", "COMPLETED_WHILE_PAUSING", null);
            }
            if (paused == DispatchPauseService.CompletionDisposition.REJECTED)
                return rejectActive(tenantId, executorId, input, hash, "COMPLETION_PAUSE_REJECTED");
        }
        String category = input.getString("failureCategory");
        boolean failover = !success && DispatchService.isExecutorFailureCategory(category);
        JSONObject handoff = input.getJSONObject("handoff");
        boolean accepted = failover
                ? dispatches.onExecutorUnavailableResult(tenantId, executorId, id, category, input.getString("error"),
                        store.countSessionRecoveryDecisions(tenantId, id))
                : dispatches.onResult(tenantId, executorId, id, success, input.getString("resultSummary"),
                        input.getString("error"), Boolean.TRUE.equals(input.getBoolean("workflowChanged")),
                        success && handoff != null, category);
        if (!accepted) return rejectActive(tenantId, executorId, input, hash, "RESULT_STATE_CONFLICT");
        if (failover) {
            guidance.requeueForExecutorFailover(tenantId, id);
            dispatches.runPending(id);
        } else if (!success) {
            guidance.failForDispatch(tenantId, id, input.getString("error"));
        }
        if (success && input.getJSONObject("workflowPlan") != null)
            workflows.apply(tenantId, id, input.getJSONObject("workflowPlan"));
        JSONObject handoffReply = null;
        if (success && handoff != null && dispatches.mayRouteHandoff(tenantId, executorId, id)) {
            HandoffResult result = handoffs.handle(tenantId, row.getWorkitemId(), id,
                    handoff.getString("to"), handoff.getString("toType"));
            if (result == null) throw new IllegalStateException("HANDOFF_RESULT_MISSING");
            if (result.status() == HandoffResult.Status.REJECTED) {
                // Optimistic conflicts are not permanent protocol decisions.
                if ("RUN_VERSION_CONFLICT".equals(result.reasonCode()) || "INTERNAL_ERROR".equals(result.reasonCode()))
                    throw new IllegalStateException(result.reasonCode());
                return rejectActive(tenantId, executorId, input, hash, "HANDOFF_" + result.reasonCode());
            }
            handoffReply = new JSONObject();
            handoffReply.put("type", "TASK_HANDOFF_RESULT");
            handoffReply.put("dispatchId", id);
            handoffReply.put("workitemId", row.getWorkitemId());
            handoffReply.put("status", result.status().name());
            handoffReply.put("downstreamDispatchId", result.downstreamDispatchId());
            handoffReply.put("targetType", result.status() == HandoffResult.Status.HUMAN_ASSIGNED ? "HUMAN" : "AGENT");
            handoffReply.put("targetRef", result.targetRef());
            handoffReply.put("reasonCode", result.reasonCode());
            handoffReply.put("message", result.message());
        }
        return persist(tenantId, executorId, input, hash, "ACCEPTED", null, handoffReply);
    }

    private Outcome rejectActive(long tenantId, long executorId, JSONObject input, String hash, String reason) {
        if (!dispatches.rejectProtocolResult(tenantId, executorId, input.getLongValue("dispatchId"), reason))
            throw new IllegalStateException("RESULT_REJECTION_NOT_DURABLE");
        guidance.failForDispatch(tenantId, input.getLongValue("dispatchId"), reason);
        return persist(tenantId, executorId, input, hash, "REJECTED", reason, null);
    }
    private Outcome persist(long tenantId, long executorId, JSONObject input, String hash,
            String disposition, String reason, JSONObject handoff) {
        JSONObject ack = acknowledgement(input, disposition, reason);
        store.insert(tenantId, executorId, input, hash, ack, handoff);
        return new Outcome(ack, handoff);
    }
    private Outcome replay(JSONObject input, long executorId, String hash, DispatchResultStore.Decision decision) {
        if (decision.executorId() != executorId) return retry(input, "RESULT_OWNER_MISMATCH");
        if (!decision.hash().equals(hash)) return retry(input, "RESULT_ID_CONFLICT");
        return new Outcome(decision.ack(), decision.handoff());
    }
    public static Outcome retry(JSONObject input, String reason) {
        return new Outcome(acknowledgement(input, "RETRYABLE", reason), null);
    }
    private static JSONObject acknowledgement(JSONObject input, String disposition, String reason) {
        JSONObject ack = new JSONObject();
        ack.put("type", "TASK_RESULT_ACK");
        ack.put("dispatchId", input.getLongValue("dispatchId"));
        ack.put("accepted", "ACCEPTED".equals(disposition));
        ack.put("resultProtocolVersion", 2);
        ack.put("resultId", input.getString("resultId"));
        ack.put("disposition", disposition);
        ack.put("retryable", "RETRYABLE".equals(disposition));
        ack.put("reasonCode", reason);
        return ack;
    }
    /** Old TASK_RESULT frames have neither V2 identity field; partial V2 claims never downgrade. */
    public static boolean isLegacyResult(JSONObject input) {
        return input != null && !input.containsKey("resultProtocolVersion") && !input.containsKey("resultId");
    }
    private static JSONObject withLegacyIdentity(long tenantId, long executorId, JSONObject input) {
        // A legacy dispatch can return to the same executor after infrastructure failover.
        // Identical results replay; a later, different outcome must get its own decision.
        JSONObject normalized = JSON.parseObject(input.toJSONString());
        normalized.put("resultProtocolVersion", 2);
        normalized.put("resultId", UUID.nameUUIDFromBytes(("autowonder/task-result/legacy/v1/"
                + tenantId + "/" + executorId + "/" + input.getLongValue("dispatchId") + "/" + payloadHash(input))
                .getBytes(StandardCharsets.UTF_8)).toString());
        return normalized;
    }
    public static boolean validResultId(String value) {
        if (value == null || value.length() != 36) return false;
        try { return UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException invalid) { return false; }
    }
    static String payloadHash(JSONObject input) {
        try {
            Map<String, Object> business = new TreeMap<>();
            for (String key : java.util.List.of("success", "resultSummary", "error", "failureCategory", "failureScope",
                    "retryable", "workflowChanged", "workflowPlan", "handoff", "checkpointSeq", "checkpointSha256")) {
                if (input.get(key) != null) business.put(key, input.get(key));
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(JSON.toJSONString(canonical(business)).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, entry) -> sorted.put(key.toString(), canonical(entry)));
            return sorted;
        }
        if (value instanceof java.util.List<?> list) return list.stream().map(DispatchResultCoordinator::canonical).toList();
        return value;
    }
    public record Outcome(JSONObject ack, JSONObject handoff) { }
}
