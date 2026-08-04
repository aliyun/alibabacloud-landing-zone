package com.aliyun.autowonder.evolution;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class EvolutionHypothesisTrialLiteService {

	private static final double TARGET_MIN_LIFT = 0.02;
	private static final double RELIABILITY_NON_INFERIORITY_MARGIN = 0.05;
	private static final double ADOPT_PROBABILITY = 0.90;
	private static final double REJECT_PROBABILITY = 0.80;
	private static final double MIN_ARM_SAMPLE = 5.0;

	private final EvolutionProposalDao proposalDao;
	private final BayesianEvidenceDao evidenceDao;
	private final BayesianEvidenceLiteService evidenceService;
	private final BayesianDecisionEngineLite decisionEngine;

	public EvolutionHypothesisTrialLiteService(EvolutionProposalDao proposalDao,
												 BayesianEvidenceDao evidenceDao,
												 BayesianEvidenceLiteService evidenceService,
												 BayesianDecisionEngineLite decisionEngine) {
		this.proposalDao = proposalDao;
		this.evidenceDao = evidenceDao;
		this.evidenceService = evidenceService;
		this.decisionEngine = decisionEngine;
	}

	public EvolutionTrialDecision startTrial(long proposalId, String taskPatternKey, long tenantId, long userId) {
		EvolutionProposalDO proposal = requireProposal(proposalId, tenantId);
		if (!"PROPOSED".equals(proposal.getStatus()) && !"VALIDATED".equals(proposal.getStatus())) {
			throw new BizException(ErrorCode.CONFLICT);
		}
		String pattern = requirePattern(taskPatternKey);
		String target = targetPosteriorType(proposal);
		JSONObject trial = new JSONObject(true);
		trial.put("taskPatternKey", pattern);
		trial.put("targetPosteriorType", target);
		trial.put("decision", "CONTINUE_TRIAL");
		trial.put("reasonCode", "trial_started");
		trial.put("baselineArm", arm("TRIAL_BASELINE", proposalId));
		trial.put("candidateArm", arm("TRIAL_CANDIDATE", proposalId));
		trial.put("decisionRule", Map.of(
				"minArmEffectiveSampleSize", MIN_ARM_SAMPLE,
				"targetMinLift", TARGET_MIN_LIFT,
				"adoptProbability", ADOPT_PROBABILITY,
				"rejectProbability", REJECT_PROBABILITY,
				"reliabilityNonInferiorityMargin", RELIABILITY_NON_INFERIORITY_MARGIN));
		proposal.setTrialJson(trial.toJSONString());
		if (proposalDao.markTrial(proposalId, tenantId, proposal.getLifecycleJson(),
				proposal.getVersion(), userId) == 0) {
			throw new BizException(ErrorCode.CONFLICT);
		}
		return decision(proposalId, "CONTINUE_TRIAL", "TRIAL", "trial_started",
				pattern, target, null, null, null, null, null);
	}

	/** Compatibility endpoint; live Runtime telemetry is the normal evidence path. */
	public EvolutionTrialDecision recordOutcome(long proposalId, EvolutionTrialEvidenceCommand command,
															 long tenantId, long userId) {
		EvolutionProposalDO proposal = requireTrialProposal(proposalId, tenantId);
		JSONObject trial = requireTrial(proposal);
		if (command == null || blank(command.getRawOutcome()) || blank(command.getSourceType())
				|| blank(command.getSourceRef())) {
			throw new BizException(ErrorCode.PARAM_INVALID);
		}
		String outcome = normalizeOutcome(command.getRawOutcome());
		BayesianEvidenceCommand evidence = new BayesianEvidenceCommand();
		evidence.setAssetType("TRIAL_CANDIDATE");
		evidence.setAssetId(proposalId);
		evidence.setPosteriorType(trial.getString("targetPosteriorType"));
		evidence.setContextKey(trial.getString("taskPatternKey"));
		evidence.setSourceType(command.getSourceType());
		evidence.setSourceRef(command.getSourceRef());
		evidence.setOutcome(outcome);
		evidence.setObservation("POSITIVE".equals(outcome) ? 1.0 : 0.0);
		evidence.setWeight(command.getWeight());
		evidence.setEvidenceJson(command.getEvidenceJson());
		evidence.setDependencyGroup("proposal:" + proposalId);
		evidence.setIdempotencyKey(blank(command.getIdempotencyKey())
				? "proposal:" + proposalId + ":" + command.getSourceRef() : command.getIdempotencyKey());
		evidenceService.record(evidence, tenantId, userId);
		return decide(proposalId, tenantId, userId);
	}

	public EvolutionTrialDecision decide(long proposalId, long tenantId, long userId) {
		EvolutionProposalDO proposal = requireTrialProposal(proposalId, tenantId);
		JSONObject trial = requireTrial(proposal);
		String context = trial.getString("taskPatternKey");
		String targetType = trial.getString("targetPosteriorType");
		BayesianEvidenceDO baselineTarget = latest(tenantId, "TRIAL_BASELINE", proposalId, targetType, context);
		BayesianEvidenceDO candidateTarget = latest(tenantId, "TRIAL_CANDIDATE", proposalId, targetType, context);
		BayesianEvidenceDO baselineReliability = "RELIABILITY".equals(targetType) ? baselineTarget
				: latest(tenantId, "TRIAL_BASELINE", proposalId, "RELIABILITY", context);
		BayesianEvidenceDO candidateReliability = "RELIABILITY".equals(targetType) ? candidateTarget
				: latest(tenantId, "TRIAL_CANDIDATE", proposalId, "RELIABILITY", context);

		if (!enough(baselineTarget, candidateTarget) || !enough(baselineReliability, candidateReliability)) {
			return decision(proposalId, "CONTINUE_TRIAL", "TRIAL", "insufficient_arm_evidence",
					context, targetType, baselineTarget, candidateTarget, null, null, null);
		}
		BayesianDecisionEngineLite.PosteriorComparison targetComparison = decisionEngine.compare(
				posterior(baselineTarget), posterior(candidateTarget), TARGET_MIN_LIFT);
		BayesianDecisionEngineLite.PosteriorComparison reliabilityGuard = decisionEngine.compare(
				posterior(baselineReliability), posterior(candidateReliability),
				-RELIABILITY_NON_INFERIORITY_MARGIN);
		BayesianDecisionEngineLite.PosteriorComparison reliabilityHarm = decisionEngine.compare(
				posterior(baselineReliability), posterior(candidateReliability),
				RELIABILITY_NON_INFERIORITY_MARGIN);

		EvolutionTrialDecision result;
		if (targetComparison.winProbability() >= ADOPT_PROBABILITY
				&& reliabilityGuard.winProbability() >= ADOPT_PROBABILITY) {
			result = decision(proposalId, "ADOPT", "TRIAL_ADOPTED", "candidate_beats_live_baseline",
					context, targetType, baselineTarget, candidateTarget, targetComparison,
					reliabilityGuard.winProbability(), null);
		} else if (reliabilityHarm.loseProbability() >= REJECT_PROBABILITY) {
			result = decision(proposalId, "REJECT", "REJECTED", "candidate_breaks_reliability_guardrail",
					context, targetType, baselineTarget, candidateTarget, targetComparison,
					reliabilityGuard.winProbability(), reliabilityHarm.loseProbability());
		} else if (targetComparison.loseProbability() >= REJECT_PROBABILITY) {
			result = decision(proposalId, "REJECT", "REJECTED", "candidate_underperforms_live_baseline",
					context, targetType, baselineTarget, candidateTarget, targetComparison,
					reliabilityGuard.winProbability(), reliabilityHarm.loseProbability());
		} else {
			return decision(proposalId, "CONTINUE_TRIAL", "TRIAL", "posterior_still_uncertain",
					context, targetType, baselineTarget, candidateTarget, targetComparison,
					reliabilityGuard.winProbability(), reliabilityHarm.loseProbability());
		}
		persistDecision(proposal, trial, result, tenantId, userId);
		recordActionOutcome(proposal, trial, result, tenantId, userId);
		return result;
	}

	/** Late sessions may finish after a trial has already adopted or rejected its candidate. */
	public EvolutionTrialDecision decideIfActive(long proposalId, long tenantId, long userId) {
		EvolutionProposalDO proposal = requireProposal(proposalId, tenantId);
		return "TRIAL".equals(proposal.getStatus()) ? decide(proposalId, tenantId, userId) : null;
	}

	private JSONObject arm(String assetType, long proposalId) {
		JSONObject arm = new JSONObject(true);
		arm.put("assetType", assetType);
		arm.put("assetId", proposalId);
		return arm;
	}

	private BayesianEvidenceDO latest(long tenantId, String assetType, long proposalId,
											String posteriorType, String context) {
		return evidenceDao.findLatest(tenantId, assetType, proposalId, posteriorType, context);
	}

	private boolean enough(BayesianEvidenceDO baseline, BayesianEvidenceDO candidate) {
		return sample(baseline) >= MIN_ARM_SAMPLE && sample(candidate) >= MIN_ARM_SAMPLE;
	}

	private double sample(BayesianEvidenceDO evidence) {
		return evidence == null || evidence.getEffectiveSampleSize() == null
				? 0.0 : evidence.getEffectiveSampleSize();
	}

	private BayesianDecisionEngineLite.BetaPosterior posterior(BayesianEvidenceDO evidence) {
		return decisionEngine.posterior(evidence == null ? null : evidence.getAlpha(),
				evidence == null ? null : evidence.getBeta(),
				evidence == null ? null : evidence.getPosteriorMean(),
				evidence == null ? null : evidence.getEffectiveSampleSize());
	}

	private EvolutionTrialDecision decision(long proposalId, String value, String status, String reason,
												 String context, String targetType,
												 BayesianEvidenceDO baseline, BayesianEvidenceDO candidate,
												 BayesianDecisionEngineLite.PosteriorComparison comparison,
												 Double reliabilityGuard, Double reliabilityHarm) {
		EvolutionTrialDecision decision = new EvolutionTrialDecision();
		decision.setProposalId(proposalId);
		decision.setDecision(value);
		decision.setProposalStatus(status);
		decision.setReasonCode(reason);
		decision.setTaskPatternKey(context);
		decision.setTargetPosteriorType(targetType);
		decision.setBaselinePosteriorMean(baseline == null ? null : baseline.getPosteriorMean());
		decision.setBaselineEffectiveSampleSize(baseline == null ? null : baseline.getEffectiveSampleSize());
		decision.setCandidatePosteriorMean(candidate == null ? null : candidate.getPosteriorMean());
		decision.setCandidateEffectiveSampleSize(candidate == null ? null : candidate.getEffectiveSampleSize());
		decision.setReliabilityGuardProbability(reliabilityGuard);
		if (comparison != null) {
			decision.setPosteriorWinProbability(comparison.winProbability());
			decision.setPosteriorLoseProbability(comparison.loseProbability());
			decision.setExpectedLift(comparison.expectedLift());
		}
		return decision;
	}

	private void persistDecision(EvolutionProposalDO proposal, JSONObject trial, EvolutionTrialDecision decision,
									 long tenantId, long userId) {
		Map<String, Object> report = new LinkedHashMap<>();
		report.put("decision", decision.getDecision());
		report.put("reasonCode", decision.getReasonCode());
		report.put("targetPosteriorType", decision.getTargetPosteriorType());
		report.put("baselinePosteriorMean", decision.getBaselinePosteriorMean());
		report.put("baselineEffectiveSampleSize", decision.getBaselineEffectiveSampleSize());
		report.put("candidatePosteriorMean", decision.getCandidatePosteriorMean());
		report.put("candidateEffectiveSampleSize", decision.getCandidateEffectiveSampleSize());
		report.put("posteriorWinProbability", decision.getPosteriorWinProbability());
		report.put("posteriorLoseProbability", decision.getPosteriorLoseProbability());
		report.put("expectedLift", decision.getExpectedLift());
		report.put("reliabilityGuardProbability", decision.getReliabilityGuardProbability());
		trial.putAll(report);
		proposal.setTrialJson(trial.toJSONString());
		if (proposalDao.markTrialDecision(proposal.getId(), tenantId, decision.getProposalStatus(),
				proposal.getLifecycleJson(), proposal.getVersion(), userId) == 0) {
			throw new BizException(ErrorCode.CONFLICT);
		}
	}

	private void recordActionOutcome(EvolutionProposalDO proposal, JSONObject trial,
										EvolutionTrialDecision decision, long tenantId, long userId) {
		String action = policyAction(proposal);
		if (blank(action)) return;
		String idempotencyKey = "proposal:" + proposal.getId() + ":action:" + action + ":decision";
		if (evidenceDao.findByIdempotencyKey(tenantId, idempotencyKey) != null) return;
		JSONObject json = new JSONObject(true);
		json.put("proposalId", proposal.getId());
		json.put("action", action);
		json.put("trialDecision", decision.getDecision());
		json.put("targetPosteriorType", decision.getTargetPosteriorType());
		BayesianEvidenceCommand evidence = new BayesianEvidenceCommand();
		evidence.setAssetType(BayesianActionPolicyLiteService.ACTION_MODEL_ASSET_TYPE);
		evidence.setAssetId(BayesianActionPolicyLiteService.ACTION_MODEL_ASSET_ID);
		evidence.setPosteriorType(BayesianActionPolicyLiteService.posteriorType(action));
		evidence.setContextKey(trial.getString("taskPatternKey"));
		evidence.setSourceType("BAYESIAN_TRIAL_DECISION");
		evidence.setSourceRef("proposal:" + proposal.getId() + ":trial");
		evidence.setOutcome("ADOPT".equals(decision.getDecision()) ? "POSITIVE" : "NEGATIVE");
		evidence.setWeight(1.0);
		evidence.setEvidenceJson(json.toJSONString());
		evidence.setDependencyGroup("proposal:" + proposal.getId());
		evidence.setIdempotencyKey(idempotencyKey);
		evidenceService.record(evidence, tenantId, userId);
	}

	private String targetPosteriorType(EvolutionProposalDO proposal) {
		try {
			JSONObject policy = JSON.parseObject(proposal.getPolicyJson());
			String target = policy == null ? null : policy.getString("targetPosteriorType");
			return blank(target) ? defaultTarget(policy == null ? null : policy.getString("action")) : target;
		} catch (RuntimeException ignored) {
			return "RELIABILITY";
		}
	}

	private String defaultTarget(String action) {
		return "COMPRESS".equalsIgnoreCase(action) ? "TOKEN_EFFICIENCY" : "RELIABILITY";
	}

	private String policyAction(EvolutionProposalDO proposal) {
		try {
			JSONObject policy = JSON.parseObject(proposal.getPolicyJson());
			return policy == null ? null : policy.getString("action");
		} catch (RuntimeException ignored) {
			return null;
		}
	}

	private EvolutionProposalDO requireTrialProposal(long proposalId, long tenantId) {
		EvolutionProposalDO proposal = requireProposal(proposalId, tenantId);
		if (!"TRIAL".equals(proposal.getStatus())) throw new BizException(ErrorCode.CONFLICT);
		return proposal;
	}

	private EvolutionProposalDO requireProposal(long proposalId, long tenantId) {
		EvolutionProposalDO proposal = proposalDao.findById(proposalId);
		if (proposal == null || proposal.getTenantId() == null || proposal.getTenantId() != tenantId) {
			throw new BizException(ErrorCode.NOT_FOUND);
		}
		return proposal;
	}

	private JSONObject requireTrial(EvolutionProposalDO proposal) {
		try {
			JSONObject trial = JSON.parseObject(proposal.getTrialJson());
			if (trial == null || blank(trial.getString("taskPatternKey"))
					|| blank(trial.getString("targetPosteriorType"))) throw new BizException(ErrorCode.CONFLICT);
			return trial;
		} catch (BizException exception) {
			throw exception;
		} catch (RuntimeException invalid) {
			throw new BizException(ErrorCode.CONFLICT);
		}
	}

	private String requirePattern(String value) {
		if (blank(value)) throw new BizException(ErrorCode.PARAM_INVALID);
		return value.trim();
	}

	private String normalizeOutcome(String raw) {
		String value = raw == null ? null : raw.trim().toUpperCase();
		if ("PASS".equals(value) || "SUCCESS".equals(value) || "POSITIVE".equals(value)) return "POSITIVE";
		if ("FAIL".equals(value) || "FAILED".equals(value) || "NEGATIVE".equals(value)) return "NEGATIVE";
		throw new BizException(ErrorCode.PARAM_INVALID);
	}

	private boolean blank(String value) {
		return value == null || value.isBlank();
	}
}
