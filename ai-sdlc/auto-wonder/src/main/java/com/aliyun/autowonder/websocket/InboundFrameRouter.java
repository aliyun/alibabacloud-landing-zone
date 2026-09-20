package com.aliyun.autowonder.websocket;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.aliyun.autowonder.artifact.ArtifactService;
import com.aliyun.autowonder.artifact.ArtifactOwnerRef;
import com.aliyun.autowonder.dispatch.ExecutionSourceType;
import com.aliyun.autowonder.aiusage.DispatchAiUsageService;
import com.aliyun.autowonder.artifact.dto.ReportArtifactRequest;
import com.aliyun.autowonder.conversation.AgentConversationService;
import com.aliyun.autowonder.conversation.ConversationCommandsService;
import com.aliyun.autowonder.conversation.ConversationTurnEventService;
import com.aliyun.autowonder.debuglog.DebugLogService;
import com.aliyun.autowonder.dispatch.DispatchService;
import com.aliyun.autowonder.dispatch.DispatchPauseService;
import com.aliyun.autowonder.dispatch.HandoffResult;
import com.aliyun.autowonder.dispatch.HandoffService;
import com.aliyun.autowonder.executor.ExecutorService;
import com.aliyun.autowonder.executor.ExecutorDispatchSnapshot;
import com.aliyun.autowonder.executor.ProviderModelCatalogService;
import com.aliyun.autowonder.guidance.GuidanceService;
import com.aliyun.autowonder.guidance.InteractionWorkflowService;
import com.aliyun.autowonder.skill.RuntimeMcpConnectionTestService;
import com.aliyun.autowonder.scheduledtask.compat.ScheduledTaskCapabilityGuard;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class InboundFrameRouter {

    private static final Logger log = LoggerFactory.getLogger(InboundFrameRouter.class);
    private static final java.util.Set<String> DISPATCH_FRAME_TYPES = java.util.Set.of(
            "TASK_ACK", "TASK_PROGRESS", "TASK_RESULT", "TASK_BUSY", "TASK_PAUSED",
            "TASK_PAUSE_FAILED", "TASK_GUIDANCE_ACK", "ARTIFACT_UPLOADED", "TASK_HANDOFF");

    private final DispatchService dispatchService;
    private final ArtifactService artifactService;
    private final PresenceManager presenceManager;
    private final HandoffService handoffService;
    private final DispatchDrainScheduler drainScheduler;
    private final DispatchPauseService pauseService;
    private final GuidanceService guidanceService;
    private final InteractionWorkflowService interactionWorkflowService;
    private final AgentConversationService agentConversationService;
    private final DispatchAiUsageService usageService;
    private final RuntimeMcpConnectionTestService runtimeMcpConnectionTestService;
    private final ExecutorService executorService;
    private ScheduledTaskCapabilityGuard capabilityGuard;
    private ConversationTurnEventService conversationTurnEventService;
    private ProviderModelCatalogService providerModelCatalogService;
    private ConversationCommandsService conversationCommandsService;
    private DebugLogService debugLogService;
    private com.aliyun.autowonder.executor.ExecutorRestartService restartService;
    @Autowired
    public void setRestartService(com.aliyun.autowonder.executor.ExecutorRestartService service) { this.restartService = service; }
    private com.aliyun.autowonder.executor.ExecutorUpdateService updateService;
    @Autowired
    public void setUpdateService(com.aliyun.autowonder.executor.ExecutorUpdateService service) { this.updateService = service; }


    @Autowired(required = false)
    public void setConversationTurnEventService(ConversationTurnEventService service) {
        this.conversationTurnEventService = service;
    }

    @Autowired(required = false)
    public void setConversationCommandsService(ConversationCommandsService conversationCommandsService) {
        this.conversationCommandsService = conversationCommandsService;
    }

    @Autowired(required = false)
    public void setProviderModelCatalogService(ProviderModelCatalogService service) {
        this.providerModelCatalogService = service;
    }

    @Autowired(required = false)
    public void setDebugLogService(DebugLogService service) {
        this.debugLogService = service;
    }

    @Autowired
    public InboundFrameRouter(DispatchService dispatchService, ArtifactService artifactService,
            PresenceManager presenceManager, HandoffService handoffService,
            DispatchDrainScheduler drainScheduler, DispatchPauseService pauseService,
            GuidanceService guidanceService, InteractionWorkflowService interactionWorkflowService,
            AgentConversationService agentConversationService, DispatchAiUsageService usageService,
            RuntimeMcpConnectionTestService runtimeMcpConnectionTestService,
            ExecutorService executorService, ScheduledTaskCapabilityGuard capabilityGuard) {
        this.dispatchService = dispatchService;
        this.artifactService = artifactService;
        this.presenceManager = presenceManager;
        this.handoffService = handoffService;
        this.drainScheduler = drainScheduler;
        this.pauseService = pauseService;
        this.guidanceService = guidanceService;
        this.interactionWorkflowService = interactionWorkflowService;
        this.agentConversationService = agentConversationService;
        this.usageService = usageService;
        this.runtimeMcpConnectionTestService = runtimeMcpConnectionTestService;
        this.executorService = executorService;
        this.capabilityGuard = capabilityGuard;
    }

    InboundFrameRouter(DispatchService dispatchService, ArtifactService artifactService,
            PresenceManager presenceManager, HandoffService handoffService,
            DispatchDrainScheduler drainScheduler, DispatchPauseService pauseService,
            GuidanceService guidanceService, InteractionWorkflowService interactionWorkflowService,
            AgentConversationService agentConversationService, DispatchAiUsageService usageService,
            RuntimeMcpConnectionTestService runtimeMcpConnectionTestService,
            ExecutorService executorService) {
        this.dispatchService = dispatchService;
        this.artifactService = artifactService;
        this.presenceManager = presenceManager;
        this.handoffService = handoffService;
        this.drainScheduler = drainScheduler;
        this.pauseService = pauseService;
        this.guidanceService = guidanceService;
        this.interactionWorkflowService = interactionWorkflowService;
        this.agentConversationService = agentConversationService;
        this.usageService = usageService;
        this.runtimeMcpConnectionTestService = runtimeMcpConnectionTestService;
        this.executorService = executorService;
    }

    /** Test/backward-compatible constructor. */
    InboundFrameRouter(DispatchService dispatchService, ArtifactService artifactService,
            PresenceManager presenceManager, HandoffService handoffService,
            DispatchDrainScheduler drainScheduler, DispatchPauseService pauseService,
            GuidanceService guidanceService, InteractionWorkflowService interactionWorkflowService,
            AgentConversationService agentConversationService, DispatchAiUsageService usageService) {
        this(dispatchService, artifactService, presenceManager, handoffService, drainScheduler,
                pauseService, guidanceService, interactionWorkflowService, agentConversationService, usageService, null, null);
    }

    InboundFrameRouter(DispatchService dispatchService, ArtifactService artifactService,
            PresenceManager presenceManager, HandoffService handoffService,
            DispatchDrainScheduler drainScheduler, DispatchPauseService pauseService,
            GuidanceService guidanceService, InteractionWorkflowService interactionWorkflowService,
            AgentConversationService agentConversationService, DispatchAiUsageService usageService,
            RuntimeMcpConnectionTestService runtimeMcpConnectionTestService) {
        this(dispatchService, artifactService, presenceManager, handoffService, drainScheduler,
                pauseService, guidanceService, interactionWorkflowService, agentConversationService, usageService,
                runtimeMcpConnectionTestService, null);
    }

    InboundFrameRouter(DispatchService dispatchService, ArtifactService artifactService,
            PresenceManager presenceManager, HandoffService handoffService,
            DispatchDrainScheduler drainScheduler, DispatchPauseService pauseService,
            GuidanceService guidanceService) {
        this(dispatchService, artifactService, presenceManager, handoffService, drainScheduler,
                pauseService, guidanceService, null, null, null, null, null);
    }

    private com.aliyun.autowonder.dispatch.DispatchRecoveryService recovery;
    @org.springframework.beans.factory.annotation.Autowired
    public void setRecovery(com.aliyun.autowonder.dispatch.DispatchRecoveryService service) { recovery = service; }
    private com.aliyun.autowonder.dispatch.DispatchRuntimeReconciler runtimeReconciler;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setRuntimeReconciler(com.aliyun.autowonder.dispatch.DispatchRuntimeReconciler value) {
        runtimeReconciler = value;
    }

    public void route(ExecutorSession es, String message) {
        JSONObject json;
        try {
            json = JSON.parseObject(message);
        } catch (Exception e) {
            log.warn("malformed frame from executor={}", es.getExecutorId());
            return;
        }
        if (json == null) {
            return;
        }
        String type = json.getString("type");
        if (type == null) {
            return;
        }
        long incomingDispatchId = json.getLongValue("dispatchId");
        if (recovery != null && incomingDispatchId > 0 && recovery.cancelRequested(es.getTenantId(), incomingDispatchId)) {
            boolean stopped = ("TASK_PAUSED".equals(type) || "TASK_RESULT".equals(type))
                    && recovery.onStopped(es.getTenantId(), es.getExecutorId(), incomingDispatchId);
            sendResultAck(es, incomingDispatchId, stopped);
            return;
        }
        DispatchBoundary durableBoundary = resolveDispatchBoundary(es, json, type);
        if (recovery != null && durableBoundary != null && recovery.cancelRequested(es.getTenantId(), durableBoundary.dispatchId())) {
            sendResultAck(es, durableBoundary.dispatchId(), false);
            return;
        }
        ArtifactOwnerRef durableOwner = durableBoundary == null ? null : durableBoundary.owner();
        switch (type) {
            case "EXECUTOR_RESTART_RESULT":
                if (restartService != null) restartService.onResult(es, json);
                break;
            case "EXECUTOR_UPGRADE_RESULT":
                if (updateService != null) updateService.onUpgradeResult(es, json);
                break;
            case "HEARTBEAT":
                log.info("inbound HEARTBEAT executorId={}", es.getExecutorId());
                java.util.Set<Long> activeConversationTurnIds = activeConversationTurnIds(json);
                java.util.List<String> protocolFeatures = protocolFeatures(json);
                boolean inventoryProtocol = protocolFeatures != null
                        && protocolFeatures.contains("dispatch_inventory_v1");
                ExecutorDispatchSnapshot dispatchSnapshot = null;
                if (inventoryProtocol) {
                    try {
                        dispatchSnapshot = dispatchSnapshot(es, json);
                        activeConversationTurnIds = dispatchSnapshot.runningConversationTurnIds();
                    } catch (IllegalArgumentException invalidInventory) {
                        String protocolError = "EXECUTOR_PROTOCOL_INCOMPATIBLE: " + invalidInventory.getMessage();
                        log.warn("executor dispatch inventory rejected executorId={} reason={}",
                                es.getExecutorId(), invalidInventory.getMessage());
                        presenceManager.recordProtocolError(es.getExecutorId(), es.getAgentId(),
                                es.getSession().getId(), protocolError);
                        closeSession(es);
                        break;
                    }
                } else {
                    java.util.List<Long> legacyRunning = runningDispatchIds(json);
                    java.util.Set<Long> running = legacyRunning == null
                            ? java.util.Set.of() : new java.util.LinkedHashSet<>(legacyRunning);
                    dispatchSnapshot = new ExecutorDispatchSnapshot(es.getSession().getId(),
                            es.getMaxConcurrentDispatches(), false, false, running, running,
                            activeConversationTurnIds, java.util.Set.of(), null, System.currentTimeMillis());
                }
                PresenceManager.SessionMutationResult heartbeatResult =
                        presenceManager.publishHeartbeat(es.getExecutorId(), es.getAgentId(),
                                es.getSession().getId(), dispatchSnapshot, protocolFeatures,
                                json.getString("version"), json.getString("model"));
                if (heartbeatResult == PresenceManager.SessionMutationResult.DELETED
                        || heartbeatResult == PresenceManager.SessionMutationResult.STALE_SESSION) {
                    log.warn("heartbeat rejected executorId={} result={}; closing session",
                            es.getExecutorId(), heartbeatResult);
                    closeSession(es);
                    break;
                }
                if (heartbeatResult == PresenceManager.SessionMutationResult.RETRY) {
                    log.warn("heartbeat publication deferred executorId={} sessionId={}",
                            es.getExecutorId(), es.getSession().getId());
                    break;
                }
                if (restartService != null) restartService.onHeartbeat(es, json);
                if (updateService != null) {
                    // Runs after recordVersion so the upgrade check reads the version just stored.
                    // Auxiliary to liveness: a failed check must not cost the executor its heartbeat.
                    try {
                        updateService.onHeartbeat(es, json);
                    } catch (Exception updateEx) {
                        log.warn("executor upgrade heartbeat hook failed executorId={}",
                                es.getExecutorId(), updateEx);
                    }
                }
                if (executorService != null) {
                    executorService.persistHeartbeatIfNeeded(es.getExecutorId(), es.getTenantId());
                }
                java.util.List<Long> reportedRunningDispatchIds = runningDispatchIds(json);
                dispatchService.renewActiveLeases(es.getTenantId(), es.getExecutorId(),
                        reportedRunningDispatchIds);
                if (dispatchSnapshot != null && dispatchSnapshot.inventoryReady()
                        && dispatchSnapshot.inventoryError() == null && runtimeReconciler != null) {
                    runtimeReconciler.request(es.getTenantId(), es.getExecutorId(), es.getAgentId(),
                            es.getSession().getId());
                } else {
                    drainScheduler.request(es.getAgentId());
                }
                if (agentConversationService != null && activeConversationTurnIds != null) {
                    if (es.consumeReplacementRecoveryPending()) {
                        agentConversationService.recoverInactiveTurnsForReplacedExecutor(
                                es.getTenantId(), es.getExecutorId(), activeConversationTurnIds);
                    } else {
                        agentConversationService.recoverStaleTurnsForExecutor(es.getTenantId(),
                                es.getExecutorId(), activeConversationTurnIds);
                    }
                }
                if (providerModelCatalogService != null && reportedRunningDispatchIds != null
                        && reportedRunningDispatchIds.isEmpty()) {
                    providerModelCatalogService.requestRefreshForExecutor(es.getExecutorId());
                }
                break;
            case "TASK_ACK":
                log.info("inbound TASK_ACK dispatchId={} executorId={}", json.getLongValue("dispatchId"), es.getExecutorId());
                dispatchService.onAck(es.getTenantId(), json.getLongValue("dispatchId"));
                guidanceService.deliverQueuedForDispatch(es.getTenantId(), json.getLongValue("dispatchId"));
                break;
            case "TASK_PROGRESS":
                log.info("inbound TASK_PROGRESS dispatchId={} executorId={} agentId={} tenantId={} eventType={} stepOrder={} stepName={}",
                        json.getLongValue("dispatchId"), es.getExecutorId(), es.getAgentId(),
                        es.getTenantId(),
                        json.getString("eventType"),
                        json.get("stepOrder") != null ? json.get("stepOrder") : json.get("order"),
                        json.get("stepName") != null ? json.get("stepName") : json.get("name"));
                dispatchService.onProgress(es.getTenantId(), json.getLongValue("dispatchId"), json);
                // ACK can race the transaction that created a side interaction. Progress is a
                // second, idempotent delivery edge once the committed guidance row is visible.
                guidanceService.deliverQueuedForDispatch(es.getTenantId(), json.getLongValue("dispatchId"));
                break;
            case "TASK_RESULT":
                boolean success = Boolean.TRUE.equals(json.getBoolean("success"));
                String failureCategory = json.getString("failureCategory");
                String failureScope = json.getString("failureScope");
                if (!success && DispatchService.isExecutorFailureCategory(failureCategory)) {
                    failureScope = "EXECUTOR";
                } else if (!success && (failureCategory == null || failureCategory.isBlank())) {
                    // Heuristic fallback for runtimes predating structured failure metadata. A
                    // runtime-declared category such as tool_hook_blocked is the first causal
                    // failure and must never be relabelled as executor infrastructure failover.
                    String classified = ExecutorFailureClassifier.classify(json.getString("error"));
                    if (classified != null) {
                        failureCategory = classified;
                        failureScope = "EXECUTOR";
                    }
                }
                log.info("inbound TASK_RESULT dispatchId={} success={} executorId={} failureCategory={} failureScope={}",
                        json.getLongValue("dispatchId"), success, es.getExecutorId(),
                        failureCategory, failureScope);
                boolean durableReceiptProtocol = json.getIntValue("checkpointReceiptVersion") >= 1;
                if (success && durableReceiptProtocol
                        && !dispatchService.hasDurableCheckpoint(es.getTenantId(),
                        json.getLongValue("dispatchId"),
                        json.getLongValue("checkpointSeq"),
                        json.getString("checkpointSha256"))) {
                    log.warn("TASK_RESULT checkpoint is not durable dispatchId={} executorId={} checkpointSeq={}",
                            json.getLongValue("dispatchId"), es.getExecutorId(),
                            json.getLongValue("checkpointSeq"));
                    sendResultAck(es, json.getLongValue("dispatchId"), false);
                    break;
                }
                if (success && !durableReceiptProtocol) {
                    log.warn("accepting legacy TASK_RESULT without durable checkpoint receipt dispatchId={} executorId={}",
                            json.getLongValue("dispatchId"), es.getExecutorId());
                }
                JSONObject embeddedHandoff = json.getJSONObject("handoff");
                boolean explicitHandoff = success && embeddedHandoff != null;
                if (success && durableReceiptProtocol) {
                    DispatchPauseService.CompletionDisposition pauseCompletion =
                            pauseService.onCompletedWhilePausing(es.getTenantId(), es.getExecutorId(),
                                    json.getLongValue("dispatchId"), json.getLongValue("checkpointSeq"),
                                    json.getString("checkpointSha256"));
                    if (DispatchPauseService.CompletionDisposition.PAUSED.equals(pauseCompletion)) {
                        guidanceService.requeueDeliveredForDispatch(es.getTenantId(),
                                json.getLongValue("dispatchId"));
                        if (interactionWorkflowService != null) {
                            interactionWorkflowService.onPaused(es.getTenantId(),
                                    json.getLongValue("dispatchId"));
                        }
                        sendResultAck(es, json.getLongValue("dispatchId"), true);
                        break;
                    }
                    if (DispatchPauseService.CompletionDisposition.REJECTED.equals(pauseCompletion)) {
                        sendResultAck(es, json.getLongValue("dispatchId"), false);
                        break;
                    }
                }
                boolean executorFailover = !success
                        && "EXECUTOR".equals(failureScope)
                        && DispatchService.isExecutorFailureCategory(failureCategory);
                boolean accepted = executorFailover
                        ? dispatchService.onExecutorUnavailableResult(es.getTenantId(), es.getExecutorId(),
                                json.getLongValue("dispatchId"), failureCategory,
                                json.getString("error"))
                        : explicitHandoff
                        ? dispatchService.onResult(es.getTenantId(), es.getExecutorId(),
                                json.getLongValue("dispatchId"),
                                true,
                                json.getString("resultSummary"),
                                json.getString("error"),
                                Boolean.TRUE.equals(json.getBoolean("workflowChanged")),
                                true,
                                failureCategory)
                        : dispatchService.onResult(es.getTenantId(), es.getExecutorId(),
                                json.getLongValue("dispatchId"),
                                success,
                                json.getString("resultSummary"),
                                json.getString("error"),
                                Boolean.TRUE.equals(json.getBoolean("workflowChanged")),
                                false,
                                failureCategory);
                // debugLog 段收尾放在 accepted 判定之前：重复投递（accepted=false，行已终态）
                // 同样能收敛 debug_log 行（outbox 重发场景，S9）。
                recordDebugLogReport(es, json, durableOwner);
                if (!accepted) {
                    // The authenticated runtime cannot make a stale/foreign result valid by retrying.
                    // ACK the terminal disposition so it can delete the durable outbox record.
                    sendResultAck(es, json.getLongValue("dispatchId"), false);
                    break;
                }
                if (executorFailover) {
                    guidanceService.requeueForExecutorFailover(es.getTenantId(),
                            json.getLongValue("dispatchId"));
                    dispatchService.runPending(json.getLongValue("dispatchId"));
                }
                if (!success && !executorFailover) {
                    guidanceService.failForDispatch(es.getTenantId(), json.getLongValue("dispatchId"),
                            json.getString("error"));
                }
                if (success && json.getJSONObject("workflowPlan") != null
                        && interactionWorkflowService != null) {
                    interactionWorkflowService.apply(es.getTenantId(), json.getLongValue("dispatchId"),
                            json.getJSONObject("workflowPlan"));
                }
                if (success && embeddedHandoff != null
                        && dispatchService.mayRouteHandoff(es.getTenantId(), es.getExecutorId(),
                                json.getLongValue("dispatchId"))) {
                    routeHandoff(es, json.getLongValue("workitemId"),
                            json.getLongValue("dispatchId"), embeddedHandoff);
                }
                sendResultAck(es, json.getLongValue("dispatchId"), true);
                break;
            case "TASK_BUSY":
                log.info("inbound TASK_BUSY dispatchId={} executorId={} reason={}",
                        json.getLongValue("dispatchId"), es.getExecutorId(), json.getString("reason"));
                dispatchService.onBusy(es.getTenantId(), es.getExecutorId(),
                        json.getLongValue("dispatchId"));
                break;
            case "TASK_PAUSED":
                log.info("inbound TASK_PAUSED dispatchId={} executorId={} checkpointSeq={}",
                        json.getLongValue("dispatchId"), es.getExecutorId(),
                        json.getLongValue("checkpointSeq"));
                boolean paused = pauseService.onPaused(es.getTenantId(), es.getExecutorId(),
                        json.getLongValue("dispatchId"), json.getLongValue("checkpointSeq"),
                        json.getString("checkpointSha256"));
                if (paused) {
                    guidanceService.requeueDeliveredForDispatch(es.getTenantId(), json.getLongValue("dispatchId"));
                    if (interactionWorkflowService != null) {
                        interactionWorkflowService.onPaused(es.getTenantId(), json.getLongValue("dispatchId"));
                    }
                }
                sendResultAck(es, json.getLongValue("dispatchId"), paused);
                break;
            case "TASK_PAUSE_FAILED":
                log.info("inbound TASK_PAUSE_FAILED dispatchId={} executorId={}",
                        json.getLongValue("dispatchId"), es.getExecutorId());
                boolean pauseFailed = pauseService.onPauseFailed(es.getTenantId(), es.getExecutorId(),
                        json.getLongValue("dispatchId"), json.getString("error"));
                sendResultAck(es, json.getLongValue("dispatchId"), pauseFailed);
                break;
            case "TASK_GUIDANCE_ACK":
                log.info("inbound TASK_GUIDANCE_ACK guidanceId={} status={} executorId={}",
                        json.getLongValue("guidanceId"), json.getString("status"), es.getExecutorId());
                JSONObject workflowPlan = json.getJSONObject("workflowPlan");
                if (workflowPlan != null && "APPLIED".equals(json.getString("status"))) {
                    try {
                        if (interactionWorkflowService == null
                                || interactionWorkflowService.applyFromExecutor(es.getTenantId(),
                                es.getExecutorId(), durableBoundary.dispatchId(), workflowPlan) == null) {
                            guidanceService.acknowledge(es.getTenantId(), es.getExecutorId(),
                                    json.getLongValue("guidanceId"), "FAILED",
                                    "正式工作流程创建失败：无法解析目标员工、SDLC 或入口步骤", null);
                            break;
                        }
                    } catch (RuntimeException routingFailure) {
                        log.warn("workflow guidance routing failed guidanceId={} dispatchId={} executorId={}",
                                json.getLongValue("guidanceId"), json.getLongValue("dispatchId"),
                                es.getExecutorId(), routingFailure);
                        guidanceService.acknowledge(es.getTenantId(), es.getExecutorId(),
                                json.getLongValue("guidanceId"), "FAILED",
                                "正式工作流程创建失败：" + routingFailure.getMessage(), null);
                        break;
                    }
                }
                guidanceService.acknowledge(es.getTenantId(), es.getExecutorId(), json.getLongValue("guidanceId"),
                        json.getString("status"), json.getString("error"), json.getString("replyMarkdown"));
                break;
            case "CONVERSATION_TURN_ACK":
                if (agentConversationService != null) {
                    agentConversationService.acknowledgeTurn(es.getTenantId(), es.getExecutorId(),
                            json.getLongValue("conversationId"), json.getLong("turnId"),
                            json.getString("status"), json.getString("error"),
                            json.getString("replyMarkdown"), json.getString("sessionId"));
                }
                break;
            case "CONVERSATION_TURN_EVENT":
                if (conversationTurnEventService != null) {
                    conversationTurnEventService.persistEvent(es.getTenantId(), es.getExecutorId(),
                            json.getLongValue("conversationId"),
                            json.getLongValue("turnId"),
                            json.getIntValue("dispatchAttempt"),
                            json.getLongValue("eventSeq"),
                            json.getIntValue("chunkIndex"),
                            json.getIntValue("chunkCount"),
                            json.getString("eventType"),
                            json.getString("payloadFragment"));
                }
                break;
            case "CONVERSATION_COMMANDS_RESULT":
                if (conversationCommandsService != null) {
                    conversationCommandsService.onResult(es.getTenantId(), es.getExecutorId(),
                            json.getLongValue("conversationId"), json.getString("status"),
                            json.getString("commands"), json.getString("error"));
                }
                break;
            case "MCP_CONNECTION_TEST_RESULT":
                if (runtimeMcpConnectionTestService != null) {
                    runtimeMcpConnectionTestService.complete(es.getTenantId(), es.getExecutorId(),
                            json.getString("testId"), Boolean.TRUE.equals(json.getBoolean("success")),
                            json.getString("message"), json.getLong("durationMs"),
                            json.getJSONArray("tools") == null ? java.util.List.of()
                                    : (java.util.List<java.util.Map<String, Object>>) (java.util.List<?>)
                                            json.getJSONArray("tools").toJavaList(java.util.Map.class));
                }
                break;
            case "QODER_MODEL_CATALOG_RESULT":
                if (providerModelCatalogService != null) {
                    providerModelCatalogService.complete(es.getTenantId(), es.getExecutorId(), json);
                }
                break;
            case "ARTIFACT_UPLOADED":
                log.info("inbound ARTIFACT_UPLOADED dispatchId={} name={} type={}",
                        json.getLong("dispatchId"), json.getString("name"), json.getString("artifactType"));
                ReportArtifactRequest req = new ReportArtifactRequest();
                req.setDispatchId(json.getLong("dispatchId"));
                ArtifactOwnerRef artifactOwner = durableOwner;
                if (artifactOwner == null) {
                    log.warn("rejecting artifact frame for unknown or foreign dispatchId={} executorId={}",
                            json.getLongValue("dispatchId"), es.getExecutorId());
                    break;
                }
                // The legacy runtime field is informational only. The durable Dispatch is
                // authoritative for both its source type and its Run/workitem id.
                req.setWorkitemId(artifactOwner.sourceId());
                req.setName(json.getString("name"));
                req.setType(json.getString("artifactType"));
                req.setOssRef(json.getString("ossRef"));
                req.setSize(json.getLong("size"));
                req.setMetaJson(json.getString("metaJson"));
                Long artifactId = artifactService.record(req, es.getTenantId(), artifactOwner);
                if (usageService != null) {
                    usageService.ingestArtifact(es.getTenantId(), artifactOwner.sourceId(),
                            json.getLongValue("dispatchId"), artifactId, json.getString("name"),
                            json.getString("ossRef"), null);
                }
                break;
            case "TASK_HANDOFF":
                log.info("inbound TASK_HANDOFF dispatchId={} workitemId={} to={} toType={}",
                        json.getLongValue("dispatchId"), json.getLongValue("workitemId"),
                        json.getString("to"), json.getString("toType"));
                routeHandoff(es, json.getLongValue("workitemId"),
                        json.getLongValue("dispatchId"), json);
                break;
            default:
                log.info("inbound unknown frame type={} executorId={}", type, es.getExecutorId());
                break;
        }
    }

    private DispatchBoundary resolveDispatchBoundary(ExecutorSession es, JSONObject json, String type) {
        if (!DISPATCH_FRAME_TYPES.contains(type)) {
            return null;
        }
        long dispatchId = json.getLongValue("dispatchId");
        DispatchBoundary boundary;
        if ("TASK_GUIDANCE_ACK".equals(type)) {
            GuidanceService.InboundAcknowledgementBinding binding =
                    guidanceService.bindingForInboundAcknowledgement(
                            es.getTenantId(), es.getExecutorId(), json.getLongValue("guidanceId"));
            boundary = binding == null ? null : new DispatchBoundary(binding.dispatchId(), binding.owner());
        } else if (dispatchId > 0) {
            boundary = new DispatchBoundary(dispatchId,
                    dispatchService.artifactOwnerForInbound(es.getTenantId(), dispatchId));
        } else {
            boundary = null;
        }
        if ("TASK_GUIDANCE_ACK".equals(type) && boundary == null) {
            throw new com.aliyun.autowonder.common.error.BizException(
                    com.aliyun.autowonder.common.error.ErrorCode.NO_PERMISSION);
        }
        ArtifactOwnerRef owner = boundary == null ? null : boundary.owner();
        if (owner != null && owner.sourceType() == ExecutionSourceType.SCHEDULED_TASK_RUN) {
            capabilityGuard.requireAvailable("daemon");
        }
        if ("TASK_GUIDANCE_ACK".equals(type)
                && dispatchId > 0 && dispatchId != boundary.dispatchId()) {
            throw new com.aliyun.autowonder.common.error.BizException(
                    com.aliyun.autowonder.common.error.ErrorCode.NO_PERMISSION);
        }
        return boundary;
    }

    private record DispatchBoundary(long dispatchId, ArtifactOwnerRef owner) {
    }

    private ExecutorDispatchSnapshot dispatchSnapshot(ExecutorSession es, JSONObject json) {
        Object capacityValue = json.get("maxConcurrentDispatches");
        if (!(capacityValue instanceof Number capacityNumber)
                || capacityNumber.intValue() < 1 || capacityNumber.intValue() > 50) {
            throw new IllegalArgumentException("INVALID_MAX_CONCURRENT_DISPATCHES");
        }
        Object readyValue = json.get("dispatchInventoryReady");
        if (!(readyValue instanceof Boolean ready)) {
            throw new IllegalArgumentException("DISPATCH_INVENTORY_READY_MISSING");
        }
        java.util.Set<Long> running = requiredLongSet(json, "runningDispatchIds", 50);
        java.util.Set<Long> owned = requiredLongSet(json, "ownedDispatchIds", 1000);
        java.util.Set<Long> conversations = requiredLongSet(json, "runningConversationTurnIds", 50);
        String inventoryError = json.getString("dispatchInventoryError");
        boolean overflow = "OWNED_DISPATCH_LIMIT_EXCEEDED".equals(inventoryError);
        if (overflow) {
            if (ready || !owned.equals(running)) {
                throw new IllegalArgumentException("INVALID_DISPATCH_INVENTORY_OVERFLOW");
            }
        } else {
            if (inventoryError != null && !inventoryError.isBlank()) {
                throw new IllegalArgumentException("UNKNOWN_DISPATCH_INVENTORY_ERROR");
            }
            if (!owned.containsAll(running)) {
                throw new IllegalArgumentException("RUNNING_DISPATCH_NOT_OWNED");
            }
        }
        return new ExecutorDispatchSnapshot(es.getSession().getId(), capacityNumber.intValue(), true, ready,
                running, owned, conversations, java.util.Set.of(), inventoryError,
                System.currentTimeMillis());
    }

    private java.util.Set<Long> requiredLongSet(JSONObject json, String field, int maxSize) {
        Object value = json.get(field);
        if (!(value instanceof com.alibaba.fastjson.JSONArray raw)) {
            throw new IllegalArgumentException(field + "_MISSING");
        }
        if (raw.size() > maxSize) {
            throw new IllegalArgumentException(field + "_TOO_LARGE");
        }
        java.util.Set<Long> ids = new java.util.LinkedHashSet<>();
        for (Object element : raw) {
            if (!(element instanceof Number number) || number.longValue() <= 0
                    || !ids.add(number.longValue())) {
                throw new IllegalArgumentException(field + "_INVALID");
            }
        }
        return java.util.Set.copyOf(ids);
    }

    private void closeSession(ExecutorSession es) {
        try {
            es.getSession().close();
        } catch (Exception closeEx) {
            log.warn("failed to close executor session executorId={}", es.getExecutorId(), closeEx);
        }
    }

    private java.util.List<Long> runningDispatchIds(JSONObject json) {
        if (!json.containsKey("runningDispatchIds")) {
            return null;
        }
        Object value = json.get("runningDispatchIds");
        if (!(value instanceof com.alibaba.fastjson.JSONArray raw)) {
            return null;
        }
        java.util.List<Long> ids = new java.util.ArrayList<>();
        for (int i = 0; i < raw.size() && i < 50; i++) {
            Object valueAtIndex = raw.get(i);
            if (!(valueAtIndex instanceof Number number)) {
                return null;
            }
            long id = number.longValue();
            if (id <= 0) {
                return null;
            }
            ids.add(id);
        }
        return ids;
    }

    private java.util.Set<Long> activeConversationTurnIds(JSONObject json) {
        if (json == null || (!json.containsKey("runningConversationTurnIds")
                && !json.containsKey("runningConversationTurns"))) {
            return null;
        }
        java.util.Set<Long> ids = new java.util.LinkedHashSet<>();
        collectLongArray(json.getJSONArray("runningConversationTurnIds"), ids);
        com.alibaba.fastjson.JSONArray turns = json.getJSONArray("runningConversationTurns");
        if (turns != null) {
            for (int i = 0; i < turns.size() && ids.size() < 50; i++) {
                JSONObject turn = turns.getJSONObject(i);
                if (turn == null) {
                    continue;
                }
                Long id = turn.getLong("turnId");
                if (id != null && id > 0) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }

    private java.util.List<String> protocolFeatures(JSONObject json) {
        com.alibaba.fastjson.JSONArray raw = json.getJSONArray("protocolFeatures");
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        java.util.List<String> features = new java.util.ArrayList<>();
        for (int i = 0; i < raw.size() && features.size() < 20; i++) {
            String feature = raw.getString(i);
            if (feature != null && !feature.isEmpty()) {
                features.add(feature);
            }
        }
        return features;
    }

    private void collectLongArray(com.alibaba.fastjson.JSONArray raw, java.util.Set<Long> ids) {
        if (raw == null) {
            return;
        }
        for (int i = 0; i < raw.size() && ids.size() < 50; i++) {
            Long id = raw.getLong(i);
            if (id != null && id > 0) {
                ids.add(id);
            }
        }
    }

    /** debugLog 段收尾是纯辅助路径：任何解析/落库失败只记 warn，不影响 TASK_RESULT 语义。 */
    private void recordDebugLogReport(ExecutorSession es, JSONObject json, ArtifactOwnerRef owner) {
        if (debugLogService == null || owner == null) {
            return;
        }
        long dispatchId = json.getLongValue("dispatchId");
        try {
            JSONObject debugLog = json.getJSONObject("debugLog");
            if (debugLog == null) {
                return;
            }
            debugLogService.recordTaskResultReport(es.getTenantId(), es.getExecutorId(),
                    dispatchId, debugLog);
        } catch (RuntimeException e) {
            log.warn("debug log report ignored dispatchId={} reason=DEBUG_LOG_REPORT_IGNORED",
                    dispatchId, e);
        }
    }

    private void sendResultAck(ExecutorSession es, long dispatchId, boolean accepted) {
        JSONObject reply = new JSONObject();
        reply.put("type", "TASK_RESULT_ACK");
        reply.put("dispatchId", dispatchId);
        reply.put("accepted", accepted);
        sendFrame(es, reply, "result ack", dispatchId);
    }

    private void routeHandoff(ExecutorSession es, long workitemId, long dispatchId, JSONObject handoff) {
        HandoffResult result;
        try {
            result = handoffService.handle(es.getTenantId(), workitemId, dispatchId,
                    handoff.getString("to"), handoff.getString("toType"));
        } catch (Exception e) {
            log.warn("handoff failed dispatchId={}", dispatchId, e);
            result = HandoffResult.rejected("INTERNAL_ERROR", e.getMessage());
        }

        JSONObject reply = new JSONObject();
        reply.put("type", "TASK_HANDOFF_RESULT");
        reply.put("dispatchId", dispatchId);
        reply.put("workitemId", workitemId);
        reply.put("status", result.status().name());
        reply.put("downstreamDispatchId", result.downstreamDispatchId());
        reply.put("targetType", targetType(result.status()));
        reply.put("targetRef", result.targetRef());
        reply.put("reasonCode", result.reasonCode());
        reply.put("message", result.message());
        sendFrame(es, reply, "handoff result", dispatchId);
    }

    private void sendFrame(ExecutorSession es, JSONObject frame, String frameName, long dispatchId) {
        try {
            es.sendText(frame.toJSONString());
        } catch (Exception e) {
            log.warn("{} send failed dispatchId={} executorId={}",
                    frameName, dispatchId, es.getExecutorId(), e);
        }
    }

    private String targetType(HandoffResult.Status status) {
        if (status == HandoffResult.Status.AGENT_DISPATCHED) {
            return "AGENT";
        }
        if (status == HandoffResult.Status.HUMAN_ASSIGNED) {
            return "HUMAN";
        }
        return null;
    }
}
