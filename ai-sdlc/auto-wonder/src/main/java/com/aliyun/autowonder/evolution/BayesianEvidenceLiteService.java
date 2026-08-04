package com.aliyun.autowonder.evolution;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import org.springframework.stereotype.Service;

@Service
public class BayesianEvidenceLiteService {

    private static final double PRIOR_ALPHA = 1.0;
    private static final double PRIOR_BETA = 1.0;

    private final BayesianEvidenceDao evidenceDao;
    private final BayesianTriggerPolicyLite triggerPolicy;
    private final BayesianCreditModelLite creditModel;

    public BayesianEvidenceLiteService(BayesianEvidenceDao evidenceDao,
                                       BayesianTriggerPolicyLite triggerPolicy,
                                       BayesianCreditModelLite creditModel) {
        this.evidenceDao = evidenceDao;
        this.triggerPolicy = triggerPolicy;
        this.creditModel = creditModel;
    }

    public BayesianEvidenceDO record(BayesianEvidenceCommand cmd, long tenantId, long userId) {
        validate(cmd);
        BayesianEvidenceDO latest = evidenceDao.findLatest(tenantId, cmd.getAssetType(), cmd.getAssetId(),
                cmd.getPosteriorType(), cmd.getContextKey());
        double alpha = latest == null || latest.getAlpha() == null ? PRIOR_ALPHA : latest.getAlpha();
        double beta = latest == null || latest.getBeta() == null ? PRIOR_BETA : latest.getBeta();
        BayesianCreditModelLite.CreditAssignment credit = creditModel.assign(cmd);
        double weight = credit.credit();
		double observation = cmd.getObservation() == null
				? ("POSITIVE".equals(cmd.getOutcome()) ? 1.0 : 0.0)
				: cmd.getObservation();
		alpha += weight * observation;
		beta += weight * (1.0 - observation);

        BayesianEvidenceDO evidence = new BayesianEvidenceDO();
        evidence.setTenantId(tenantId);
        evidence.setAssetType(cmd.getAssetType());
        evidence.setAssetId(cmd.getAssetId());
        evidence.setPosteriorType(cmd.getPosteriorType());
        evidence.setContextKey(cmd.getContextKey());
        evidence.setSourceType(cmd.getSourceType());
        evidence.setSourceRef(cmd.getSourceRef());
        evidence.setOutcome(cmd.getOutcome());
        evidence.setWeight(weight);
		evidence.setEvidenceJson(evidenceJson(credit.evidenceJson(), observation));
        evidence.setDependencyGroup(cmd.getDependencyGroup());
        evidence.setIdempotencyKey(cmd.getIdempotencyKey());
        evidence.setAlpha(alpha);
        evidence.setBeta(beta);
        evidence.setPosteriorMean(alpha / (alpha + beta));
        evidence.setEffectiveSampleSize(alpha + beta - PRIOR_ALPHA - PRIOR_BETA);
        evidence.setCreatorId(userId);
        evidenceDao.insert(evidence);
        return evidence;
    }

    public BayesianTriggerDecision checkTrigger(long tenantId, BayesianTriggerCheckRequest req) {
        if (req == null || blank(req.getAssetType()) || req.getAssetId() == null
                || blank(req.getPosteriorType()) || blank(req.getContextKey())) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        double minSample = req.getMinEffectiveSampleSize() == null ? 5.0 : req.getMinEffectiveSampleSize();
        double threshold = req.getCredibleUpperBoundBelow() == null ? 0.30 : req.getCredibleUpperBoundBelow();
        BayesianEvidenceDO latest = evidenceDao.findLatest(tenantId, req.getAssetType(), req.getAssetId(),
                req.getPosteriorType(), req.getContextKey());
        return decision(latest, minSample, threshold);
    }

    private BayesianTriggerDecision decision(BayesianEvidenceDO latest, double minSample, double threshold) {
        BayesianTriggerDecision decision = new BayesianTriggerDecision();
        if (latest != null) {
            decision.setPosteriorMean(latest.getPosteriorMean());
            decision.setEffectiveSampleSize(latest.getEffectiveSampleSize());
            if (latest.getAlpha() != null && latest.getBeta() != null) {
                decision.setCredibleUpperBound90(
                        triggerPolicy.credibleUpperBound90(latest.getAlpha(), latest.getBeta()));
            }
        }
        decision.setShouldInvestigate(triggerPolicy.shouldInvestigate(latest, minSample, threshold));
        return decision;
    }

    private void validate(BayesianEvidenceCommand cmd) {
        if (cmd == null || blank(cmd.getAssetType()) || cmd.getAssetId() == null
                || blank(cmd.getPosteriorType()) || blank(cmd.getContextKey())
                || blank(cmd.getSourceType()) || blank(cmd.getSourceRef())
                || blank(cmd.getOutcome())) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        if (!"POSITIVE".equals(cmd.getOutcome()) && !"NEGATIVE".equals(cmd.getOutcome())) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
		if (cmd.getObservation() != null && (cmd.getObservation() < 0.0 || cmd.getObservation() > 1.0
				|| cmd.getObservation().isNaN())) {
			throw new BizException(ErrorCode.PARAM_INVALID);
		}
        if (cmd.getEvidenceJson() != null && !cmd.getEvidenceJson().isBlank()) {
            try {
                JSON.parse(cmd.getEvidenceJson());
            } catch (RuntimeException e) {
                throw new BizException(ErrorCode.PARAM_INVALID);
            }
        }
    }

	private String evidenceJson(String raw, double observation) {
		JSONObject evidence = new JSONObject(true);
		if (raw != null && !raw.isBlank()) {
			Object parsed = JSON.parse(raw);
			if (parsed instanceof JSONObject object) {
				evidence.putAll(object);
			} else {
				evidence.put("raw", parsed);
			}
		}
		evidence.put("observation", observation);
		return evidence.toJSONString();
	}

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
