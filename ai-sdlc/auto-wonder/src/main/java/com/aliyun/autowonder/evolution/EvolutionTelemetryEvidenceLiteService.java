package com.aliyun.autowonder.evolution;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.aliyun.autowonder.agent.AgentSkillDO;
import com.aliyun.autowonder.agent.AgentSkillDao;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.dispatch.DispatchDO;
import com.aliyun.autowonder.dispatch.DispatchDao;
import com.aliyun.autowonder.dispatch.DispatchRuntimeEventDO;
import com.aliyun.autowonder.dispatch.DispatchRuntimeEventDao;
import com.aliyun.autowonder.dispatch.DispatchStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class EvolutionTelemetryEvidenceLiteService {

	private static final long COHORT_ASSET_ID = 0L;

	private final DispatchDao dispatchDao;
	private final DispatchRuntimeEventDao runtimeEventDao;
	private final AgentSkillDao agentSkillDao;
	private final EvidenceLedgerLiteService ledgerService;
	private final BayesianPolicyLiteService policyService;
	private final EvolutionHistoricalPercentileLiteService percentileService;
	private final EvolutionHypothesisTrialLiteService trialService;

	public EvolutionTelemetryEvidenceLiteService(DispatchDao dispatchDao,
												 DispatchRuntimeEventDao runtimeEventDao,
												 AgentSkillDao agentSkillDao,
												 EvidenceLedgerLiteService ledgerService,
												 BayesianPolicyLiteService policyService,
												 EvolutionHistoricalPercentileLiteService percentileService,
												 EvolutionHypothesisTrialLiteService trialService) {
		this.dispatchDao = dispatchDao;
		this.runtimeEventDao = runtimeEventDao;
		this.agentSkillDao = agentSkillDao;
		this.ledgerService = ledgerService;
		this.policyService = policyService;
		this.percentileService = percentileService;
		this.trialService = trialService;
	}

	public EvolutionTelemetryIngestResult ingestDispatch(long dispatchId, Boolean successOverride,
															 String resultSummary, String error,
															 long tenantId, long userId) {
		DispatchDO dispatch = dispatchDao.findById(dispatchId);
		if (dispatch == null || dispatch.getTenantId() == null || dispatch.getTenantId() != tenantId) {
			throw new BizException(ErrorCode.PARAM_INVALID);
		}
		List<DispatchRuntimeEventDO> events = runtimeEventDao.listByDispatch(tenantId, dispatchId);
		Trajectory trajectory = summarize(dispatch, events == null ? List.of() : events,
				successOverride, resultSummary, error);
		if (trajectory.skillIds.isEmpty()) {
			trajectory.skillIds.addAll(frozenSkillBundle(dispatch));
			trajectory.bundleSource = trajectory.skillIds.isEmpty() ? "EMPTY" : "AGENT_VERSION_FALLBACK";
		}
		EvolutionTelemetryIngestResult result = trajectory.toResult(dispatchId);
		if (!trajectory.eligible) {
			return result;
		}

		List<MetricEvidence> metrics = metricEvidence(tenantId, trajectory);
		boolean candidateTrial = trajectory.trialId != null && "CANDIDATE".equals(trajectory.trialArm);
		for (MetricEvidence metric : metrics) {
			if (!candidateTrial) {
				record(dispatchId, tenantId, userId, trajectory, result,
						"SKILL_COHORT", COHORT_ASSET_ID, metric, 1.0);
			}
			if (!candidateTrial && !trajectory.skillIds.isEmpty()) {
				double bundleShare = 1.0 / trajectory.skillIds.size();
				for (Long skillId : trajectory.skillIds) {
					record(dispatchId, tenantId, userId, trajectory, result,
							"SKILL", skillId, metric, bundleShare);
				}
			}
			if (trajectory.trialId != null && ("BASELINE".equals(trajectory.trialArm)
					|| "CANDIDATE".equals(trajectory.trialArm))) {
				record(dispatchId, tenantId, userId, trajectory, result,
						"TRIAL_" + trajectory.trialArm, trajectory.trialId, metric, 1.0);
			}
		}
		for (Long skillId : candidateTrial ? Set.<Long>of() : trajectory.skillIds) {
			BayesianPolicyRequest request = new BayesianPolicyRequest();
			request.setAssetType("SKILL");
			request.setAssetId(skillId);
			request.setPosteriorType("RELIABILITY");
			request.setContextKey(trajectory.taskPatternKey);
			result.getSkillDecisions().add(policyService.decide(tenantId, request));
		}
		if (trajectory.trialId != null && trialService != null) {
			trialService.decideIfActive(trajectory.trialId, tenantId, userId);
		}
		return result;
	}

	private void record(long dispatchId, long tenantId, long userId, Trajectory trajectory,
						EvolutionTelemetryIngestResult result, String assetType, Long assetId,
						MetricEvidence metric, double weight) {
		EvidenceLedgerEventCommand event = new EvidenceLedgerEventCommand();
		event.setAssetType(assetType);
		event.setAssetId(assetId);
		event.setPosteriorType(metric.posteriorType());
		event.setContextKey(trajectory.taskPatternKey);
		event.setSourceType("RUNTIME_TELEMETRY");
		event.setSourceRef("dispatch:" + dispatchId + ":trajectory");
		event.setRawOutcome(metric.observation() >= 0.5 ? "POSITIVE" : "NEGATIVE");
		event.setObservation(metric.observation());
		event.setWeight(weight);
		event.setRawEventJson(trajectory.evidenceJson(metric));
		event.setDependencyGroup("dispatch:" + dispatchId + ":trajectory");
		event.setIdempotencyKey("dispatch:" + dispatchId + ":" + assetType.toLowerCase(Locale.ROOT)
				+ ":" + assetId + ":" + metric.posteriorType().toLowerCase(Locale.ROOT));
		BayesianEvidenceDO evidence = ledgerService.recordEvent(event, tenantId, userId);
		if (evidence != null && evidence.getId() != null) {
			result.getEvidenceIds().add(evidence.getId());
		}
	}

	private List<MetricEvidence> metricEvidence(long tenantId, Trajectory t) {
		List<MetricEvidence> metrics = new ArrayList<>();
		if (!t.environmentFailure) {
			metrics.add(new MetricEvidence("RELIABILITY", "success", "POSITIVE".equals(t.outcome) ? 1.0 : 0.0));
		}
		metrics.add(relativeMetric(tenantId, t, "TOKEN_EFFICIENCY", "totalTokens", t.totalTokens));
		metrics.add(relativeMetric(tenantId, t, "TURN_EFFICIENCY", "turns", t.turns));
		metrics.add(relativeMetric(tenantId, t, "REPAIR_EFFICIENCY", "repairs", t.repairs));
		metrics.add(relativeMetric(tenantId, t, "TOOL_EFFICIENCY", "inefficientToolCalls",
				t.toolFailures + t.repeatToolCalls));
		if (t.alignment != null) {
			metrics.add(new MetricEvidence("ALIGNMENT", "humanAlignment", t.alignment));
		}
		return metrics;
	}

	private MetricEvidence relativeMetric(long tenantId, Trajectory t, String posteriorType,
											 String metricName, double rawValue) {
		double observation = percentileService.lowerIsBetter(
				tenantId, t.taskPatternKey, posteriorType, rawValue);
		return new MetricEvidence(posteriorType, metricName, observation, rawValue);
	}

	private Set<Long> frozenSkillBundle(DispatchDO dispatch) {
		if (dispatch.getAgentVersionId() == null) {
			return Set.of();
		}
		List<AgentSkillDO> rows = agentSkillDao.listByVersion(dispatch.getAgentVersionId());
		Set<Long> result = new LinkedHashSet<>();
		if (rows != null) {
			for (AgentSkillDO row : rows) {
				if (row != null && row.getSkillId() != null && row.getSkillId() > 0) {
					result.add(row.getSkillId());
				}
			}
		}
		return result;
	}

	private Trajectory summarize(DispatchDO dispatch, List<DispatchRuntimeEventDO> events,
									 Boolean successOverride, String resultSummary, String error) {
		Trajectory t = new Trajectory();
		t.eligible = canonical(dispatch.getResumeMode());
		t.outcome = successOverride != null
				? (successOverride ? "POSITIVE" : "NEGATIVE")
				: (DispatchStatus.SUCCEEDED.equals(dispatch.getStatus()) ? "POSITIVE" : "NEGATIVE");
		t.resultSummary = resultSummary == null ? dispatch.getResultSummary() : resultSummary;
		t.error = error == null ? dispatch.getError() : error;
		t.bundleSource = "RUNTIME_SKILL_LOADED";
		Map<String, Integer> toolSignatures = new LinkedHashMap<>();
		Map<String, Long> loadedSkillIdsByName = new LinkedHashMap<>();
		Date firstEvent = null;
		Date lastEvent = null;
		for (DispatchRuntimeEventDO event : events) {
			JSONObject detail = parse(event.getDetailJson());
			String eventType = event.getEventType();
			t.eventTypes.add(eventType);
			if ("skill.loaded".equals(eventType)) {
				Long skillId = detail.getLong("skillId");
				addLong(t.skillIds, skillId);
				if (skillId != null && !blank(detail.getString("name"))) {
					loadedSkillIdsByName.put(detail.getString("name").trim().toLowerCase(Locale.ROOT), skillId);
				}
			}
			if ("skill.invoked".equals(eventType)) {
				addLong(t.invokedSkillIds, detail.getLong("skillId"));
			}
			if ("agent.tool_use".equals(eventType) && "skill".equalsIgnoreCase(detail.getString("tool"))) {
				JSONObject input = detail.getJSONObject("input");
				String skillName = input == null ? null : input.getString("skill");
				if (!blank(skillName)) {
					addLong(t.invokedSkillIds, loadedSkillIdsByName.get(skillName.trim().toLowerCase(Locale.ROOT)));
				}
			}
			if ("turn.started".equals(eventType)) {
				t.turns++;
			}
			if (isToolCall(eventType)) {
				t.toolCalls++;
				String signature = toolSignature(eventType, detail);
				int seen = toolSignatures.getOrDefault(signature, 0);
				if (seen > 0) {
					t.repeatToolCalls++;
				}
				toolSignatures.put(signature, seen + 1);
			}
			if (isToolResult(eventType) && isFailure(eventType, event, detail)) {
				t.toolFailures++;
			}
			if (isRepair(eventType, detail)) {
				t.repairs++;
			}
			t.interruptions += "session.interrupted".equals(eventType)
					|| "turn.interrupted".equals(eventType) ? 1 : 0;
			t.resumes += "session.resumed".equals(eventType) ? 1 : 0;
			t.forks += "session.forked".equals(eventType) ? 1 : 0;
			t.inputTokens += longValue(detail, "inputTokens", "promptTokens");
			t.outputTokens += longValue(detail, "outputTokens", "completionTokens");
			t.taskType = first(t.taskType, detail.getString("taskType"));
			t.primaryRepoGroup = first(t.primaryRepoGroup, detail.getString("primaryRepoGroup"));
			t.operation = first(t.operation, detail.getString("operation"));
			t.explicitTaskPatternKey = first(t.explicitTaskPatternKey,
					detail.getString("taskPatternKey"), detail.getString("contextKey"));
			t.failureCategory = first(t.failureCategory, detail.getString("errorCategory"),
					detail.getString("failureCategory"));
			if (t.trialId == null) {
				t.trialId = safeLong(detail, "trialId", "proposalId");
			}
			t.trialArm = first(t.trialArm, upper(detail.getString("trialArm")));
			if (detail.getString("alignmentOutcome") != null) {
				t.alignment = alignment(detail.getString("alignmentOutcome"));
			}
			if (event.getEventTime() != null) {
				firstEvent = firstEvent == null || event.getEventTime().before(firstEvent) ? event.getEventTime() : firstEvent;
				lastEvent = lastEvent == null || event.getEventTime().after(lastEvent) ? event.getEventTime() : lastEvent;
			}
			t.durationFallbackMs += eventType != null && eventType.startsWith("turn.")
					&& !"turn.started".equals(eventType) ? longValue(detail, "durationMs") : 0;
		}
		if ("COMMENT_REWORK".equalsIgnoreCase(dispatch.getResumeMode())) {
			t.alignment = 0.0;
			t.repairs++;
		}
		t.totalTokens = t.inputTokens + t.outputTokens;
		t.elapsedMs = firstEvent != null && lastEvent != null
				? Math.max(0, lastEvent.getTime() - firstEvent.getTime()) : t.durationFallbackMs;
		t.taskPatternKey = taskPatternKey(t);
		t.environmentFailure = "NEGATIVE".equals(t.outcome)
				&& environmentFailure(first(t.failureCategory, t.error));
		t.skillNeedSignals = signals(t);
		return t;
	}

	private boolean canonical(String resumeMode) {
		return !("SIDE_INTERACTION".equalsIgnoreCase(resumeMode)
				|| "CANONICAL_INTERACTION".equalsIgnoreCase(resumeMode)
				|| "COMMENT_INTERACTION".equalsIgnoreCase(resumeMode));
	}

	private List<String> signals(Trajectory t) {
		List<String> signals = new ArrayList<>();
		if ("NEGATIVE".equals(t.outcome) && !t.environmentFailure) signals.add("agent_failure");
		if (t.repairs > 0) signals.add("repair_observed");
		if (t.toolFailures > 0) signals.add("tool_failure_observed");
		if (t.repeatToolCalls > 0) signals.add("repeated_tool_call");
		if (t.alignment != null && t.alignment == 0.0) signals.add("human_rework");
		return signals;
	}

	private String taskPatternKey(Trajectory t) {
		if (!blank(t.explicitTaskPatternKey)) {
			return t.explicitTaskPatternKey.trim();
		}
		return key(first(t.taskType, "dispatch")) + ":"
				+ key(first(t.primaryRepoGroup, "unknown")) + ":"
				+ key(first(t.operation, "execute"));
	}

	private boolean environmentFailure(String category) {
		if (blank(category)) return false;
		String value = category.toLowerCase(Locale.ROOT);
		return value.contains("permission") || value.contains("network") || value.contains("rate_limit")
				|| value.contains("quota") || value.contains("unauthorized") || value.contains("authentication")
				|| value.contains("connection") || value.contains("dns") || value.contains("unavailable");
	}

	private Double alignment(String value) {
		if (blank(value)) return null;
		return switch (value.trim().toUpperCase(Locale.ROOT)) {
			case "ACCEPT", "ACCEPTED", "APPROVE", "APPROVED" -> 1.0;
			case "REJECT", "REJECTED", "REWORK", "TAKEOVER" -> 0.0;
			default -> null;
		};
	}

	private String toolSignature(String eventType, JSONObject detail) {
		return eventType + "|" + first(detail.getString("tool"), detail.getString("name"), "unknown")
				+ "|" + first(detail.getString("inputSummary"), "");
	}

	private boolean isToolCall(String type) {
		return "agent.tool_use".equals(type) || "mcp.call".equals(type)
				|| "cli.call".equals(type) || "bash.call".equals(type);
	}

	private boolean isToolResult(String type) {
		return "agent.tool_result".equals(type) || "mcp.result".equals(type)
				|| "cli.result".equals(type) || "bash.result".equals(type);
	}

	private boolean isFailure(String type, DispatchRuntimeEventDO event, JSONObject detail) {
		return contains(type, "fail") || !blank(event.getError()) || !blank(detail.getString("error"))
				|| "failed".equalsIgnoreCase(detail.getString("status"))
				|| Boolean.TRUE.equals(detail.getBoolean("isError"));
	}

	private boolean isRepair(String type, JSONObject detail) {
		return "step.fix_required".equals(type) || contains(type, "retry") || contains(type, "repair")
				|| contains(detail.getString("type"), "retry") || contains(detail.getString("type"), "repair");
	}

	private long longValue(JSONObject object, String... keys) {
		for (String key : keys) {
			Long value = object.getLong(key);
			if (value != null) return value;
		}
		return 0L;
	}

	private Long safeLong(JSONObject object, String... keys) {
		for (String key : keys) {
			try {
				Long value = object.getLong(key);
				if (value != null) return value;
			} catch (RuntimeException ignored) {
				// Non-numeric ephemeral candidate IDs are not asset IDs.
			}
		}
		return null;
	}

	private String upper(String value) {
		return blank(value) ? null : value.trim().toUpperCase(Locale.ROOT);
	}

	private void addLong(Set<Long> target, Long value) {
		if (value != null && value > 0) target.add(value);
	}

	private JSONObject parse(String json) {
		if (blank(json)) return new JSONObject(true);
		try {
			Object parsed = JSON.parse(json);
			return parsed instanceof JSONObject object ? object : new JSONObject(true);
		} catch (RuntimeException ignored) {
			return new JSONObject(true);
		}
	}

	private String key(String value) {
		return value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
				.replaceAll("(^-+|-+$)", "");
	}

	private String first(String... values) {
		for (String value : values) if (!blank(value)) return value;
		return null;
	}

	private boolean blank(String value) {
		return value == null || value.isBlank();
	}

	private boolean contains(String value, String needle) {
		return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
	}

	private record MetricEvidence(String posteriorType, String metricName, double observation, Double rawMetricValue) {
		MetricEvidence(String posteriorType, String metricName, double observation) {
			this(posteriorType, metricName, observation, null);
		}

		MetricEvidence(String posteriorType, String metricName, double observation, double rawMetricValue) {
			this(posteriorType, metricName, observation, Double.valueOf(rawMetricValue));
		}
	}

	private static class Trajectory {
		boolean eligible;
		boolean environmentFailure;
		String bundleSource;
		String taskPatternKey;
		String explicitTaskPatternKey;
		String taskType;
		String primaryRepoGroup;
		String operation;
		String outcome;
		String resultSummary;
		String error;
		String failureCategory;
		Double alignment;
		Long trialId;
		String trialArm;
		long inputTokens;
		long outputTokens;
		long totalTokens;
		long turns;
		long toolCalls;
		long toolFailures;
		long repeatToolCalls;
		long repairs;
		long interruptions;
		long resumes;
		long forks;
		long elapsedMs;
		long durationFallbackMs;
		Set<Long> skillIds = new LinkedHashSet<>();
		Set<Long> invokedSkillIds = new LinkedHashSet<>();
		List<String> eventTypes = new ArrayList<>();
		List<String> skillNeedSignals = new ArrayList<>();

		String evidenceJson(MetricEvidence metric) {
			JSONObject root = new JSONObject(true);
			root.put("source", "dispatch_runtime_event");
			root.put("taskPatternKey", taskPatternKey);
			root.put("bundleSource", bundleSource);
			root.put("skillBundle", skillIds);
			root.put("invokedSkillIds", invokedSkillIds);
			root.put("eventTypes", eventTypes);
			root.put("metricName", metric.metricName());
			root.put("rawMetricValue", metric.rawMetricValue());
			root.put("observation", metric.observation());
			root.put("outcome", outcome);
			root.put("environmentFailure", environmentFailure);
			root.put("trialId", trialId);
			root.put("trialArm", trialArm);
			root.put("failureCategory", failureCategory);
			JSONObject metrics = new JSONObject(true);
			metrics.put("totalTokens", totalTokens);
			metrics.put("turns", turns);
			metrics.put("repairs", repairs);
			metrics.put("toolCalls", toolCalls);
			metrics.put("toolFailures", toolFailures);
			metrics.put("repeatToolCalls", repeatToolCalls);
			metrics.put("elapsedMs", elapsedMs);
			metrics.put("interruptions", interruptions);
			metrics.put("resumes", resumes);
			metrics.put("forks", forks);
			root.put("metrics", metrics);
			return root.toJSONString();
		}

		EvolutionTelemetryIngestResult toResult(long dispatchId) {
			EvolutionTelemetryIngestResult result = new EvolutionTelemetryIngestResult();
			result.setDispatchId(dispatchId);
			result.setEligible(eligible);
			result.setTaskPatternKey(taskPatternKey);
			result.setOutcome(outcome);
			result.setFailureMode(failureCategory);
			result.setTotalTokens(totalTokens);
			result.setTurns(turns);
			result.setRepairs(repairs);
			result.setToolFailures(toolFailures);
			result.setRepeatToolCalls(repeatToolCalls);
			result.setElapsedMs(elapsedMs);
			result.setSkillIds(new ArrayList<>(skillIds));
			result.setInvokedSkillIds(new ArrayList<>(invokedSkillIds));
			result.setSkillNeedSignals(skillNeedSignals);
			return result;
		}
	}
}
