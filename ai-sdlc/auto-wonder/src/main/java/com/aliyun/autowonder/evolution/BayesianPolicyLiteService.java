package com.aliyun.autowonder.evolution;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class BayesianPolicyLiteService {

	private static final int LOOKBACK_LIMIT = 100;
	private static final double DEFAULT_MIN_SAMPLE = 5.0;
	private static final double DECISION_PROBABILITY = 0.80;
	private static final double RELIABILITY_NON_INFERIORITY_MARGIN = 0.05;
	private static final List<String> DIMENSIONS = List.of(
			"RELIABILITY", "TOKEN_EFFICIENCY", "TURN_EFFICIENCY",
			"REPAIR_EFFICIENCY", "TOOL_EFFICIENCY", "ALIGNMENT");

	private final BayesianEvidenceDao evidenceDao;
	private final BayesianDecisionEngineLite decisionEngine;
	private final BayesianActionPolicyLiteService actionPolicy;

	public BayesianPolicyLiteService(BayesianEvidenceDao evidenceDao,
									 BayesianDecisionEngineLite decisionEngine,
									 BayesianActionPolicyLiteService actionPolicy) {
		this.evidenceDao = evidenceDao;
		this.decisionEngine = decisionEngine;
		this.actionPolicy = actionPolicy;
	}

	public BayesianPolicyDecision decide(long tenantId, BayesianPolicyRequest request) {
		validate(request);
		String targetContext = request.getContextKey();
		double minSample = request.getMinEffectiveSampleSize() == null
				? DEFAULT_MIN_SAMPLE : request.getMinEffectiveSampleSize();
		Map<String, Map<String, BayesianEvidenceDO>> skill = load(
				tenantId, "SKILL", request.getAssetId());
		Map<String, Map<String, BayesianEvidenceDO>> cohort = load(
				tenantId, "SKILL_COHORT", 0L);

		Pair reliability = pair(skill, cohort, "RELIABILITY", targetContext);
		if (!comparable(reliability, minSample)) {
			return explore("insufficient_comparative_evidence", targetContext, reliability);
		}

		Map<String, Double> deficits = new LinkedHashMap<>();
		for (String dimension : DIMENSIONS) {
			Pair current = pair(skill, cohort, dimension, targetContext);
			if (comparable(current, minSample)) {
				deficits.put(dimension, deficitProbability(current));
			}
		}
		double reliabilityDeficit = deficits.getOrDefault("RELIABILITY", 0.0);
		double repairDeficit = deficits.getOrDefault("REPAIR_EFFICIENCY", 0.0);
		double toolDeficit = deficits.getOrDefault("TOOL_EFFICIENCY", 0.0);
		double tokenDeficit = deficits.getOrDefault("TOKEN_EFFICIENCY", 0.0);
		double turnDeficit = deficits.getOrDefault("TURN_EFFICIENCY", 0.0);
		double nonInferiorProbability = nonInferiorProbability(reliability);

		ContextShape contextShape = reliabilityShape(skill.get("RELIABILITY"),
				cohort.get("RELIABILITY"), targetContext, minSample);
		List<String> eligible = new ArrayList<>();
		String reason;
		if (max(reliabilityDeficit, repairDeficit, toolDeficit) >= DECISION_PROBABILITY) {
			eligible.add("PATCH");
			if (contextShape.poorContextCount() >= 2 && contextShape.healthyContextCount() == 0) {
				eligible.add("RETIRE");
				reason = "credible_multi_context_deficit";
			} else if (contextShape.divergenceProbability() >= DECISION_PROBABILITY) {
				eligible.add("SPLIT");
				reason = "credible_context_divergence";
			} else {
				reason = "credible_localized_deficit";
			}
		} else if (nonInferiorProbability >= DECISION_PROBABILITY
				&& max(tokenDeficit, turnDeficit) >= DECISION_PROBABILITY) {
			eligible.add("COMPRESS");
			reason = "reliability_non_inferior_efficiency_deficit";
		} else {
			return explore("posterior_not_actionable", targetContext, reliability,
					deficits, nonInferiorProbability, contextShape);
		}

		BayesianActionPolicyLiteService.ActionSelection selected = actionPolicy.select(
				tenantId, targetContext, eligible);
		return actionDecision(selected, eligible, reason, targetContext, reliability, deficits,
				nonInferiorProbability, contextShape);
	}

	private Map<String, Map<String, BayesianEvidenceDO>> load(long tenantId, String assetType, long assetId) {
		Map<String, Map<String, BayesianEvidenceDO>> result = new LinkedHashMap<>();
		for (String dimension : DIMENSIONS) {
			List<BayesianEvidenceDO> rows = evidenceDao.listRecentByAsset(
					tenantId, assetType, assetId, dimension, LOOKBACK_LIMIT);
			Map<String, BayesianEvidenceDO> byContext = new LinkedHashMap<>();
			if (rows != null) {
				for (BayesianEvidenceDO row : rows) {
					if (row != null && !blank(row.getContextKey())) {
						byContext.putIfAbsent(row.getContextKey(), row);
					}
				}
			}
			result.put(dimension, byContext);
		}
		return result;
	}

	private ContextShape reliabilityShape(Map<String, BayesianEvidenceDO> skill,
											 Map<String, BayesianEvidenceDO> cohort,
											 String targetContext, double minSample) {
		if (skill == null || cohort == null) {
			return new ContextShape(0, 0, 0.0);
		}
		Set<String> contexts = new LinkedHashSet<>(skill.keySet());
		contexts.retainAll(cohort.keySet());
		int poor = 0;
		int healthy = 0;
		double divergence = 0.0;
		BayesianEvidenceDO target = skill.get(targetContext);
		for (String context : contexts) {
			Pair current = new Pair(skill.get(context), cohort.get(context));
			if (!comparable(current, minSample)) continue;
			double deficit = deficitProbability(current);
			if (deficit >= DECISION_PROBABILITY) poor++;
			if (nonInferiorProbability(current) >= DECISION_PROBABILITY) healthy++;
			if (target != null && !context.equals(targetContext)
					&& sample(target) >= minSample && sample(skill.get(context)) >= minSample) {
				divergence = Math.max(divergence, comparison(target, skill.get(context), 0.0).winProbability());
			}
		}
		return new ContextShape(poor, healthy, divergence);
	}

	private Pair pair(Map<String, Map<String, BayesianEvidenceDO>> skill,
						Map<String, Map<String, BayesianEvidenceDO>> cohort,
						String dimension, String context) {
		return new Pair(skill.getOrDefault(dimension, Map.of()).get(context),
				cohort.getOrDefault(dimension, Map.of()).get(context));
	}

	private boolean comparable(Pair pair, double minSample) {
		return pair != null && pair.skill() != null && pair.cohort() != null
				&& sample(pair.skill()) >= minSample && sample(pair.cohort()) >= minSample;
	}

	private double deficitProbability(Pair pair) {
		return comparison(pair.skill(), pair.cohort(), 0.0).winProbability();
	}

	private double nonInferiorProbability(Pair pair) {
		return comparison(pair.cohort(), pair.skill(), -RELIABILITY_NON_INFERIORITY_MARGIN).winProbability();
	}

	private BayesianDecisionEngineLite.PosteriorComparison comparison(
			BayesianEvidenceDO baseline, BayesianEvidenceDO candidate, double minLift) {
		return decisionEngine.compare(posterior(baseline), posterior(candidate), minLift);
	}

	private BayesianDecisionEngineLite.BetaPosterior posterior(BayesianEvidenceDO row) {
		return decisionEngine.posterior(row == null ? null : row.getAlpha(), row == null ? null : row.getBeta(),
				row == null ? null : row.getPosteriorMean(), row == null ? null : row.getEffectiveSampleSize());
	}

	private BayesianPolicyDecision actionDecision(BayesianActionPolicyLiteService.ActionSelection selection,
													 List<String> eligibleActions,
													 String reason, String context, Pair reliability,
													 Map<String, Double> deficits, double nonInferior,
													 ContextShape shape) {
		BayesianPolicyDecision decision = baseDecision(selection.action(), true, reason, context, reliability);
		JSONObject policy = JSON.parseObject(decision.getPolicyJson());
		policy.putAll(comparisonJson(deficits, nonInferior, shape));
		policy.put("eligibleActions", eligibleActions);
		policy.put("targetPosteriorType", targetPosteriorType(selection.action(), deficits));
		policy.put("actionPosteriorMean", selection.posteriorMean());
		policy.put("actionEffectiveSampleSize", selection.effectiveSampleSize());
		policy.put("actionExplorationScore", selection.explorationScore());
		decision.setPolicyJson(policy.toJSONString());
		return decision;
	}

	private String targetPosteriorType(String action, Map<String, Double> deficits) {
		List<String> candidates = switch (action) {
			case "COMPRESS" -> List.of("TOKEN_EFFICIENCY", "TURN_EFFICIENCY");
			case "PATCH" -> List.of("RELIABILITY", "REPAIR_EFFICIENCY", "TOOL_EFFICIENCY");
			default -> List.of("RELIABILITY");
		};
		String best = candidates.get(0);
		for (String current : candidates) {
			if (deficits.getOrDefault(current, 0.0) > deficits.getOrDefault(best, 0.0)) best = current;
		}
		return best;
	}

	private BayesianPolicyDecision explore(String reason, String context, Pair reliability) {
		return explore(reason, context, reliability, Map.of(), 0.0, new ContextShape(0, 0, 0.0));
	}

	private BayesianPolicyDecision explore(String reason, String context, Pair reliability,
											 Map<String, Double> deficits, double nonInferior, ContextShape shape) {
		BayesianPolicyDecision decision = baseDecision("EXPLORE", false, reason, context, reliability);
		JSONObject policy = JSON.parseObject(decision.getPolicyJson());
		policy.putAll(comparisonJson(deficits, nonInferior, shape));
		decision.setPolicyJson(policy.toJSONString());
		return decision;
	}

	private BayesianPolicyDecision baseDecision(String action, boolean evolve, String reason,
												String context, Pair reliability) {
		BayesianPolicyDecision decision = new BayesianPolicyDecision();
		decision.setAction(action);
		decision.setShouldEvolve(evolve);
		decision.setReasonCode(reason);
		decision.setReason(reason);
		decision.setTargetContextKey(context);
		decision.setDominantFailureMode(failureMode(reliability == null ? null : reliability.skill()));
		decision.setRewriteBrief(rewriteBrief(action, context, decision.getDominantFailureMode()));
		BayesianEvidenceDO target = reliability == null ? null : reliability.skill();
		decision.setPosteriorMean(target == null ? null : target.getPosteriorMean());
		decision.setEffectiveSampleSize(target == null ? null : target.getEffectiveSampleSize());
		double confidence = target == null ? 0.0 : Math.min(0.99, sample(target) / (sample(target) + 10.0));
		decision.setConfidence(confidence);
		Map<String, Object> policy = new LinkedHashMap<>();
		policy.put("action", action);
		policy.put("reasonCode", reason);
		policy.put("targetContextKey", context);
		policy.put("rewriteBrief", decision.getRewriteBrief());
		policy.put("posteriorMean", decision.getPosteriorMean());
		policy.put("effectiveSampleSize", decision.getEffectiveSampleSize());
		policy.put("decisionProbability", DECISION_PROBABILITY);
		policy.put("reliabilityNonInferiorityMargin", RELIABILITY_NON_INFERIORITY_MARGIN);
		decision.setPolicyJson(JSON.toJSONString(policy));
		return decision;
	}

	private Map<String, Object> comparisonJson(Map<String, Double> deficits, double nonInferior,
													 ContextShape shape) {
		Map<String, Object> result = new LinkedHashMap<>();
		for (Map.Entry<String, Double> entry : deficits.entrySet()) {
			String key = entry.getKey().toLowerCase() + "DeficitProbability";
			if ("RELIABILITY".equals(entry.getKey())) key = "reliabilityDeficitProbability";
			result.put(key, entry.getValue());
		}
		result.put("reliabilityNonInferiorProbability", nonInferior);
		result.put("poorContextCount", shape.poorContextCount());
		result.put("healthyContextCount", shape.healthyContextCount());
		result.put("contextDivergenceProbability", shape.divergenceProbability());
		return result;
	}

	private String rewriteBrief(String action, String context, String failureMode) {
		if ("EXPLORE".equals(action)) return "Collect more comparable session evidence before changing the Skill.";
		String focus = blank(failureMode) ? "" : " Focus on " + failureMode + ".";
		return switch (action) {
			case "PATCH" -> "Patch the Skill for " + context + "." + focus;
			case "SPLIT" -> "Split the Skill at the " + context + " boundary and preserve healthy contexts." + focus;
			case "RETIRE" -> "Trial the bundle without this Skill because it is poor across contexts." + focus;
			case "COMPRESS" -> "Compress the Skill while preserving reliability for " + context + ".";
			default -> "Prepare a bounded Skill candidate from posterior evidence.";
		};
	}

	private String failureMode(BayesianEvidenceDO evidence) {
		if (evidence == null || blank(evidence.getEvidenceJson())) return null;
		try {
			JSONObject object = JSON.parseObject(evidence.getEvidenceJson());
			return object == null ? null : object.getString("failureCategory");
		} catch (RuntimeException ignored) {
			return null;
		}
	}

	private double sample(BayesianEvidenceDO evidence) {
		return evidence == null || evidence.getEffectiveSampleSize() == null
				? 0.0 : evidence.getEffectiveSampleSize();
	}

	private double max(double... values) {
		double max = 0.0;
		for (double value : values) max = Math.max(max, value);
		return max;
	}

	private void validate(BayesianPolicyRequest request) {
		if (request == null || !"SKILL".equalsIgnoreCase(request.getAssetType())
				|| request.getAssetId() == null || request.getAssetId() <= 0 || blank(request.getContextKey())) {
			throw new BizException(ErrorCode.PARAM_INVALID);
		}
	}

	private boolean blank(String value) {
		return value == null || value.isBlank();
	}

	private record Pair(BayesianEvidenceDO skill, BayesianEvidenceDO cohort) {
	}

	private record ContextShape(int poorContextCount, int healthyContextCount, double divergenceProbability) {
	}
}
