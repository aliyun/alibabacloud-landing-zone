package com.aliyun.autowonder.scheduledtask;

import com.aliyun.autowonder.json.JSON;
import com.aliyun.autowonder.json.JSONArray;
import com.aliyun.autowonder.json.JSONObject;
import com.aliyun.autowonder.agent.AgentDO;
import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.dispatch.*;
import com.aliyun.autowonder.executor.ExecutorDO;
import com.aliyun.autowonder.executor.ExecutorDao;
import com.aliyun.autowonder.sdlc.SdlcStepDO;
import com.aliyun.autowonder.sdlc.SdlcStepDao;
import com.aliyun.autowonder.workitem.DispatchStepTimings;
import com.aliyun.autowonder.workitem.dto.*;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class ScheduledTaskRunDeliveryProgressService {
    private final DispatchDao dispatchDao;
    private final DispatchRuntimeEventDao eventDao;
    private final SdlcStepDao stepDao;
    private final AgentDao agentDao;
    private final ExecutorDao executorDao;
    private final com.aliyun.autowonder.aiusage.DispatchAiUsageDao usageDao;

    public ScheduledTaskRunDeliveryProgressService(DispatchDao dispatchDao, DispatchRuntimeEventDao eventDao,
                                                   SdlcStepDao stepDao, AgentDao agentDao, ExecutorDao executorDao,
                                                   com.aliyun.autowonder.aiusage.DispatchAiUsageDao usageDao) {
        this.dispatchDao = dispatchDao;
        this.eventDao = eventDao;
        this.stepDao = stepDao;
        this.agentDao = agentDao;
        this.executorDao = executorDao;
        this.usageDao = usageDao;
    }

    public DeliveryProgressVO getDeliveryProgress(long tenantId, ScheduledTaskRunDO run) {
        // 1. Get SDLC step definitions
        List<SdlcStepDO> stepDefs = resolveStepDefs(run);

        // 2. Get all dispatches for this run
        List<DispatchDO> dispatches = dispatchDao.listBySource(tenantId,
                ExecutionSourceType.SCHEDULED_TASK_RUN.name(), run.getId());
        if (dispatches == null) dispatches = Collections.emptyList();

        // 3. Get runtime events, kept per dispatch so each execution can be projected alone
        Map<Long, List<DispatchRuntimeEventDO>> eventsByDispatch = new LinkedHashMap<>();
        List<DispatchRuntimeEventDO> allEvents = new ArrayList<>();
        for (DispatchDO dispatch : dispatches) {
            List<DispatchRuntimeEventDO> events = eventDao.listByDispatch(tenantId, dispatch.getId());
            if (events == null) events = Collections.emptyList();
            eventsByDispatch.put(dispatch.getId(), events);
            allEvents.addAll(events);
        }

        // 4. Build step VOs
        Map<Long, String> executorNames = new HashMap<>();
        List<DeliveryStepVO> steps = buildSteps(stepDefs, dispatches, allEvents, run, executorNames);

        // 5. Build process graph
        ProcessGraphVO processGraph = buildProcessGraph(dispatches, stepDefs);

        // 6. Build agent progress
        Long agentId = run.getCurrentAgentId() != null ? run.getCurrentAgentId() : run.getInitialAgentId();
        AgentDeliveryProgressVO agentProgress = new AgentDeliveryProgressVO();
        agentProgress.setAgentId(agentId);
        if (agentId != null) {
            AgentDO agent = agentDao.findById(agentId);
            if (agent != null) agentProgress.setAgentName(agent.getName());
        }
        agentProgress.setStatus(switch (run.getStatus()) {
            case "CANCELED" -> "cancelled";
            case "FAILED" -> "failed";
            case "SUCCEEDED" -> "finished";
            default -> resolveAgentProgressStatus(steps);
        });
        agentProgress.setSteps(steps);
        enrichUsage(tenantId, dispatches, agentProgress);

        // 7. Calculate total duration: sum of pure agent dispatch durations.
        // Run wall-clock (startedAt→finishedAt) includes non-agent gaps between steps
        // and must not be displayed as "Agents耗时".
        long totalDurationMs = 0;
        boolean hasAnyDuration = false;
        for (DispatchDO dispatch : dispatches) {
            Long duration = dispatchDuration(dispatch);
            if (duration != null) {
                totalDurationMs += duration;
                hasAnyDuration = true;
            }
        }
        Long agentTotalDurationMs = hasAnyDuration ? totalDurationMs : null;
        agentProgress.setDurationMs(agentTotalDurationMs);

        // 8. Assemble result. Every execution carries its own SDLC stage snapshot so the
        // duration and detail views render one selected dispatch without borrowing another
        // dispatch's stages, waits or run wall-clock.
        Date timingNow = new Date();
        List<DispatchAttemptVO> executions = new ArrayList<>();
        for (DispatchDO dispatch : dispatches) {
            DispatchAttemptVO attempt = buildExecution(dispatch, executorNames);
            if (!isInteractionDispatch(dispatch) && !stepDefs.isEmpty()) {
                attempt.setStepTimings(DispatchStepTimings.build(dispatch,
                        eventsByDispatch.getOrDefault(dispatch.getId(), List.of()), stepDefs, timingNow));
            }
            executions.add(attempt);
        }
        DeliveryProgressVO vo = new DeliveryProgressVO();
        vo.setExecutions(executions);
        vo.setSteps(steps);
        vo.setAgents(List.of(agentProgress));
        vo.setProcessGraph(processGraph);
        vo.setTotalDurationMs(agentTotalDurationMs);
        return vo;
    }

    private DispatchAttemptVO buildExecution(DispatchDO dispatch, Map<Long, String> executorNames) {
        DispatchAttemptVO attempt = new DispatchAttemptVO();
        attempt.setDispatchId(dispatch.getId());
        attempt.setAgentId(dispatch.getAgentId());
        attempt.setResumeMode(dispatch.getResumeMode());
        attempt.setStatus(dispatch.getStatus());
        attempt.setError(dispatch.getError());
        attempt.setStartedAt(dispatch.getGmtCreate());
        attempt.setEndedAt(DispatchStatus.isTerminal(dispatch.getStatus()) ? dispatch.getGmtModified() : null);
        attempt.setDurationMs(dispatchDuration(dispatch));
        attempt.setCanContinue(!DispatchStatus.isTerminal(dispatch.getStatus()));
        attempt.setCanPause(DispatchStatus.isPauseable(dispatch.getStatus()));

        if (dispatch.getAgentId() != null) {
            AgentDO agent = agentDao.findById(dispatch.getAgentId());
            if (agent != null) attempt.setAgentName(agent.getName());
        }
        attempt.setExecutorId(dispatch.getExecutorId());
        attempt.setExecutorName(executorName(dispatch.getExecutorId(), executorNames));
        return attempt;
    }

    private String executorName(Long executorId, Map<Long, String> cache) {
        if (executorId == null) {
            return null;
        }
        if (!cache.containsKey(executorId)) {
            ExecutorDO executor = executorDao.findById(executorId);
            cache.put(executorId, executor == null ? null : executor.getName());
        }
        return cache.get(executorId);
    }

    private Long dispatchDuration(DispatchDO dispatch) {
        if (dispatch == null || dispatch.getGmtCreate() == null || dispatch.getGmtModified() == null) {
            return null;
        }
        return Math.max(0L, dispatch.getGmtModified().getTime() - dispatch.getGmtCreate().getTime());
    }

    // 交互型 dispatch 不承载 SDLC 阶段，不产出阶段快照（口径同 WorkitemService.isInteractionDispatch）。
    private boolean isInteractionDispatch(DispatchDO dispatch) {
        return dispatch != null && ("SIDE_INTERACTION".equals(dispatch.getResumeMode())
                || "CANONICAL_INTERACTION".equals(dispatch.getResumeMode())
                || "COMMENT_INTERACTION".equals(dispatch.getResumeMode()));
    }

    // 前端仅识别小写展示态（见 WorkitemService.resolveAgentProgressStatus 同口径），
    // 直接透传 run 状态枚举会导致已完成数字人被回退显示为「未执行」。
    private String resolveAgentProgressStatus(List<DeliveryStepVO> steps) {
        boolean hasPaused = steps.stream().anyMatch(s -> "paused".equals(s.getStatus()));
        if (hasPaused) {
            return "paused";
        }
        boolean hasActive = steps.stream().anyMatch(s -> "active".equals(s.getStatus()));
        if (hasActive) {
            return "active";
        }
        boolean hasFailed = steps.stream().anyMatch(s -> "failed".equals(s.getStatus()));
        boolean hasDone = steps.stream().anyMatch(s -> "done".equals(s.getStatus()));
        if (hasFailed) {
            return "failed";
        }
        if (hasDone) {
            return "finished";
        }
        return "pending";
    }

    private List<DeliveryStepVO> buildSteps(List<SdlcStepDO> stepDefs, List<DispatchDO> dispatches,
                                            List<DispatchRuntimeEventDO> allEvents, ScheduledTaskRunDO run,
                                            Map<Long, String> executorNames) {
        List<DeliveryStepVO> steps = new ArrayList<>();
        for (SdlcStepDO def : stepDefs) {
            DeliveryStepVO stepVO = new DeliveryStepVO();
            stepVO.setStepId(def.getId());
            stepVO.setStepKey(def.getCode());
            stepVO.setName(def.getName());

            // Determine status from events
            String status = determineStepStatus(def.getId(), allEvents, run);
            stepVO.setStatus(status);

            // Build attempts from dispatches matching this step
            List<DispatchAttemptVO> attempts = new ArrayList<>();
            for (DispatchDO dispatch : dispatches) {
                if (def.getId() != null && def.getId().equals(dispatch.getSdlcStepId())) {
                    DispatchAttemptVO attempt = buildExecution(dispatch, executorNames);
                    attempts.add(attempt);
                }
            }
            stepVO.setAttempts(attempts);

            // Calculate step duration from events
            stepVO.setDurationMs(calculateStepDuration(def.getId(), allEvents));

            steps.add(stepVO);
        }
        return steps;
    }

    private String determineStepStatus(Long stepId, List<DispatchRuntimeEventDO> allEvents, ScheduledTaskRunDO run) {
        boolean hasStarted = false;
        boolean hasCompleted = false;
        boolean hasFailed = false;

        for (DispatchRuntimeEventDO event : allEvents) {
            if (!stepIdMatches(stepId, event)) continue;
            String eventType = event.getEventType();
            if (eventType == null) continue;
            if (eventType.contains("step.completed")) {
                hasCompleted = true;
            } else if (eventType.contains("step.started")) {
                hasStarted = true;
            } else if (eventType.contains("step.failed")) {
                hasFailed = true;
            }
        }

        if (hasCompleted) return "done";
        if (hasFailed) return "failed";
        if ("CANCELED".equals(run.getStatus())) return "cancelled";
        if (hasStarted) {
            if ("FAILED".equals(run.getStatus())) return "failed";
            if ("SUCCEEDED".equals(run.getStatus())) return "done";
            String runStatus = run.getStatus();
            if ("PAUSED".equals(runStatus)) return "paused";
            return "active";
        }
        return "pending";
    }

    private boolean stepIdMatches(Long stepId, DispatchRuntimeEventDO event) {
        // Check direct stepId field on event
        if (event.getStepId() != null && event.getStepId().equals(stepId)) return true;
        // Check detailJson for stepId
        if (event.getDetailJson() != null) {
            try {
                JSONObject detail = JSON.parseObject(event.getDetailJson());
                if (detail != null) {
                    Long eventStepId = detail.getLong("stepId");
                    if (stepId != null && stepId.equals(eventStepId)) return true;
                }
            } catch (Exception ignored) {}
        }
        return false;
    }

    private Long calculateStepDuration(Long stepId, List<DispatchRuntimeEventDO> allEvents) {
        Date startTime = null;
        Date endTime = null;
        for (DispatchRuntimeEventDO event : allEvents) {
            if (!stepIdMatches(stepId, event)) continue;
            String eventType = event.getEventType();
            if (eventType == null) continue;
            if (eventType.contains("step.started") && event.getEventTime() != null) {
                if (startTime == null || event.getEventTime().before(startTime)) {
                    startTime = event.getEventTime();
                }
            }
            if ((eventType.contains("step.completed") || eventType.contains("step.failed"))
                    && event.getEventTime() != null) {
                if (endTime == null || event.getEventTime().after(endTime)) {
                    endTime = event.getEventTime();
                }
            }
        }
        if (startTime != null && endTime != null) {
            return endTime.getTime() - startTime.getTime();
        }
        return null;
    }

    private ProcessGraphVO buildProcessGraph(List<DispatchDO> dispatches, List<SdlcStepDO> stepDefs) {
        ProcessGraphVO graph = new ProcessGraphVO();
        List<ProcessGraphNodeVO> nodes = new ArrayList<>();
        List<ProcessGraphEdgeVO> edges = new ArrayList<>();

        Map<Long, String> stepNameMap = stepDefs.stream()
                .filter(s -> s.getId() != null)
                .collect(Collectors.toMap(SdlcStepDO::getId, SdlcStepDO::getName, (a, b) -> a));

        for (DispatchDO dispatch : dispatches) {
            ProcessGraphNodeVO node = new ProcessGraphNodeVO();
            node.setKey("dispatch-" + dispatch.getId());
            node.setDispatchId(dispatch.getId());
            node.setAgentId(dispatch.getAgentId());
            node.setStepId(dispatch.getSdlcStepId());
            node.setStatus(dispatch.getStatus());
            node.setStartedAt(dispatch.getGmtCreate());

            if (dispatch.getSdlcStepId() != null) {
                node.setStepName(stepNameMap.get(dispatch.getSdlcStepId()));
            }
            if (dispatch.getAgentId() != null) {
                AgentDO agent = agentDao.findById(dispatch.getAgentId());
                if (agent != null) node.setAgentName(agent.getName());
            }
            nodes.add(node);

            // Build CONTINUE edge from resumeFromDispatchId
            if (dispatch.getResumeFromDispatchId() != null) {
                ProcessGraphEdgeVO edge = new ProcessGraphEdgeVO();
                edge.setSourceKey("dispatch-" + dispatch.getResumeFromDispatchId());
                edge.setTargetKey("dispatch-" + dispatch.getId());
                edge.setType("CONTINUE");
                edge.setSourceDispatchId(dispatch.getResumeFromDispatchId());
                edge.setTargetDispatchId(dispatch.getId());
                edges.add(edge);
            }
        }

        graph.setNodes(nodes);
        graph.setEdges(edges);
        return graph;
    }

    private List<SdlcStepDO> resolveStepDefs(ScheduledTaskRunDO run) {
        // Try from SDLC directly
        if (run.getSdlcId() != null) {
            List<SdlcStepDO> sdlcSteps = stepDao.listBySdlc(run.getSdlcId());
            if (sdlcSteps != null && !sdlcSteps.isEmpty()) {
                return sdlcSteps.stream()
                        .sorted(Comparator.comparingInt(s -> s.getStepOrder() != null ? s.getStepOrder() : 0))
                        .collect(Collectors.toList());
            }
        }

        // Fallback: try extracting from executionSnapshotJson
        if (run.getExecutionSnapshotJson() != null) {
            try {
                JSONObject snapshot = JSON.parseObject(run.getExecutionSnapshotJson());
                if (snapshot != null) {
                    JSONArray agentContexts = snapshot.getJSONArray("agentContexts");
                    if (agentContexts != null && !agentContexts.isEmpty()) {
                        JSONObject firstCtx = agentContexts.getJSONObject(0);
                        if (firstCtx != null) {
                            JSONObject sdlc = firstCtx.getJSONObject("sdlc");
                            if (sdlc != null) {
                                JSONArray stepsArr = sdlc.getJSONArray("steps");
                                if (stepsArr != null) {
                                    List<SdlcStepDO> defs = new ArrayList<>();
                                    for (int i = 0; i < stepsArr.size(); i++) {
                                        JSONObject stepObj = stepsArr.getJSONObject(i);
                                        if (stepObj != null) {
                                            SdlcStepDO def = new SdlcStepDO();
                                            def.setId(stepObj.getLong("id"));
                                            def.setCode(stepObj.getString("code"));
                                            def.setName(stepObj.getString("name"));
                                            // 快照里的步骤没有 stepOrder，事件只能按 id/code/name 归位；补序号保证顺序稳定。
                                            def.setStepOrder(i + 1);
                                            defs.add(def);
                                        }
                                    }
                                    return defs;
                                }
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        return Collections.emptyList();
    }

    private void enrichUsage(long tenantId, List<DispatchDO> dispatches, AgentDeliveryProgressVO agent) {
        if (dispatches.isEmpty()) return;
        List<Long> dispatchIds = dispatches.stream()
                .map(DispatchDO::getId).filter(Objects::nonNull).collect(Collectors.toList());
        if (dispatchIds.isEmpty()) return;
        try {
            List<com.aliyun.autowonder.aiusage.DispatchAiUsageDO> rows = usageDao.listByDispatchIds(tenantId, dispatchIds);
            if (rows == null || rows.isEmpty()) return;
            var vo = new com.aliyun.autowonder.aiusage.dto.StepUsageSummaryVO();
            long input = 0, output = 0, cacheRead = 0, reasoning = 0;
            java.math.BigDecimal credits = java.math.BigDecimal.ZERO;
            String model = null;
            for (var row : rows) {
                input += row.getInputTokens() != null ? row.getInputTokens() : 0;
                output += row.getOutputTokens() != null ? row.getOutputTokens() : 0;
                cacheRead += row.getCacheReadTokens() != null ? row.getCacheReadTokens() : 0;
                reasoning += row.getReasoningTokens() != null ? row.getReasoningTokens() : 0;
                if (row.getCredits() != null) credits = credits.add(row.getCredits());
                if (model == null && row.getModel() != null) model = row.getModel();
            }
            vo.setModel(model);
            vo.setInputTokens(input);
            vo.setOutputTokens(output);
            vo.setCacheReadTokens(cacheRead);
            vo.setReasoningTokens(reasoning);
            vo.setCredits(credits.compareTo(java.math.BigDecimal.ZERO) == 0 ? null : credits);
            agent.setUsage(vo);
        } catch (RuntimeException ignored) {
        }
    }
}
