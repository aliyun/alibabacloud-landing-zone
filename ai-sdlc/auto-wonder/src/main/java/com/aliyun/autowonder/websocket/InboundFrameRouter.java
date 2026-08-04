package com.aliyun.autowonder.websocket;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.aliyun.autowonder.artifact.ArtifactService;
import com.aliyun.autowonder.aiusage.DispatchAiUsageService;
import com.aliyun.autowonder.artifact.dto.ReportArtifactRequest;
import com.aliyun.autowonder.conversation.AgentConversationService;
import com.aliyun.autowonder.conversation.ConversationTurnEventService;
import com.aliyun.autowonder.dispatch.DispatchService;
import com.aliyun.autowonder.dispatch.DispatchPauseService;
import com.aliyun.autowonder.dispatch.HandoffResult;
import com.aliyun.autowonder.dispatch.HandoffService;
import com.aliyun.autowonder.executor.ExecutorService;
import com.aliyun.autowonder.guidance.GuidanceService;
import com.aliyun.autowonder.guidance.InteractionWorkflowService;
import com.aliyun.autowonder.skill.RuntimeMcpConnectionTestService;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class InboundFrameRouter {

    private static final Logger log = LoggerFactory.getLogger(InboundFrameRouter.class);

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
    private ConversationTurnEventService conversationTurnEventService;

    @Autowired(required = false)
    public void setConversationTurnEventService(ConversationTurnEventService service) {
        this.conversationTurnEventService = service;
    }

    @Autowired
    public InboundFrameRouter(DispatchService dispatchService, ArtifactService artifactService,
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
    public InboundFrameRouter(DispatchService dispatchService, ArtifactService artifactService,
            PresenceManager presenceManager, HandoffService handoffService,
            DispatchDrainScheduler drainScheduler, DispatchPauseService pauseService,
            GuidanceService guidanceService, InteractionWorkflowService interactionWorkflowService,
            AgentConversationService agentConversationService, DispatchAiUsageService usageService) {
        this(dispatchService, artifactService, presenceManager, handoffService, drainScheduler,
                pauseService, guidanceService, interactionWorkflowService, agentConversationService, usageService, null, null);
    }

    public InboundFrameRouter(DispatchService dispatchService, ArtifactService artifactService,
            PresenceManager presenceManager, HandoffService handoffService,
            DispatchDrainScheduler drainScheduler, DispatchPauseService pauseService,
            GuidanceService guidanceService, InteractionWorkflowService interactionWorkflowService,
            AgentConversationService agentConversationService, DispatchAiUsageService usageService,
            RuntimeMcpConnectionTestService runtimeMcpConnectionTestService) {
        this(dispatchService, artifactService, presenceManager, handoffService, drainScheduler,
                pauseService, guidanceService, interactionWorkflowService, agentConversationService, usageService,
                runtimeMcpConnectionTestService, null);
    }

    public InboundFrameRouter(DispatchService dispatchService, ArtifactService artifactService,
            PresenceManager presenceManager, HandoffService handoffService,
            DispatchDrainScheduler drainScheduler, DispatchPauseService pauseService,
            GuidanceService guidanceService) {
        this(dispatchService, artifactService, presenceManager, handoffService, drainScheduler,
                pauseService, guidanceService, null, null, null, null, null);
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
        switch (type) {
            case "HEARTBEAT":
                log.info("inbound HEARTBEAT executorId={}", es.getExecutorId());
                java.util.Set<Long> activeConversationTurnIds = activeConversationTurnIds(json);
                java.util.List<String> protocolFeatures = protocolFeatures(json);
                boolean alive;
                if (activeConversationTurnIds == null) {
                    alive = presenceManager.heartbeat(es.getExecutorId(), es.getAgentId(),
                            es.getMaxConcurrentDispatches());
                } else if (protocolFeatures == null) {
                    alive = presenceManager.heartbeat(es.getExecutorId(), es.getAgentId(),
                            es.getMaxConcurrentDispatches(), activeConversationTurnIds);
                } else {
                    alive = presenceManager.heartbeat(es.getExecutorId(), es.getAgentId(),
                            es.getMaxConcurrentDispatches(), activeConversationTurnIds, protocolFeatures);
                }
                if (!alive) {
                    log.warn("heartbeat rejected for deleted executor {}; closing session",
                            es.getExecutorId());
                    try {
                        es.getSession().close();
                    } catch (Exception closeEx) {
                        log.warn("failed to close session for deleted executor {}",
                                es.getExecutorId(), closeEx);
                    }
                    break;
                }
                presenceManager.refreshSession(es.getExecutorId(), es.getSession().getId());
                if (executorService != null) {
                    executorService.persistHeartbeatIfNeeded(es.getExecutorId(), es.getTenantId());
                }
                dispatchService.renewActiveLeases(es.getTenantId(), es.getExecutorId(),
                        runningDispatchIds(json));
                drainScheduler.request(es.getAgentId());
                if (agentConversationService != null && activeConversationTurnIds != null) {
                    if (es.consumeReplacementRecoveryPending()) {
                        agentConversationService.recoverInactiveTurnsForReplacedExecutor(
                                es.getTenantId(), es.getExecutorId(), activeConversationTurnIds);
                    } else {
                        agentConversationService.recoverStaleTurnsForExecutor(es.getTenantId(),
                                es.getExecutorId(), activeConversationTurnIds);
                    }
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
                } else if (!success) {
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
                                true)
                        : dispatchService.onResult(es.getTenantId(), es.getExecutorId(),
                                json.getLongValue("dispatchId"),
                                success,
                                json.getString("resultSummary"),
                                json.getString("error"),
                                Boolean.TRUE.equals(json.getBoolean("workflowChanged")));
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
                                es.getExecutorId(), json.getLongValue("dispatchId"), workflowPlan) == null) {
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
            case "MCP_CONNECTION_TEST_RESULT":
                if (runtimeMcpConnectionTestService != null) {
                    runtimeMcpConnectionTestService.complete(es.getTenantId(), es.getExecutorId(),
                            json.getString("testId"), Boolean.TRUE.equals(json.getBoolean("success")),
                            json.getString("message"), json.getLong("durationMs"));
                }
                break;
            case "ARTIFACT_UPLOADED":
                log.info("inbound ARTIFACT_UPLOADED dispatchId={} name={} type={}",
                        json.getLong("dispatchId"), json.getString("name"), json.getString("artifactType"));
                ReportArtifactRequest req = new ReportArtifactRequest();
                req.setDispatchId(json.getLong("dispatchId"));
                req.setWorkitemId(json.getLong("workitemId"));
                req.setName(json.getString("name"));
                req.setType(json.getString("artifactType"));
                req.setOssRef(json.getString("ossRef"));
                req.setSize(json.getLong("size"));
                req.setMetaJson(json.getString("metaJson"));
                Long artifactId = artifactService.record(req, es.getTenantId());
                if (usageService != null) {
                    usageService.ingestArtifact(es.getTenantId(), json.getLongValue("workitemId"),
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

    private java.util.List<Long> runningDispatchIds(JSONObject json) {
        java.util.List<Long> ids = new java.util.ArrayList<>();
        com.alibaba.fastjson.JSONArray raw = json.getJSONArray("runningDispatchIds");
        if (raw == null) {
            return ids;
        }
        for (int i = 0; i < raw.size() && ids.size() < 50; i++) {
            Long id = raw.getLong(i);
            if (id != null) {
                ids.add(id);
            }
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
