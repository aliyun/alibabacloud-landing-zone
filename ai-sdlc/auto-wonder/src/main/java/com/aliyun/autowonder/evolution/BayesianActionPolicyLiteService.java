package com.aliyun.autowonder.evolution;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Learns action utility from completed Bayesian Trials. State is stored in the existing
 * evolution_evidence ledger as Beta posteriors, so action learning needs no second model table.
 */
@Service
public class BayesianActionPolicyLiteService {

    static final String ACTION_MODEL_ASSET_TYPE = "SKILL_ACTION";
    static final long ACTION_MODEL_ASSET_ID = 0L;

    private final BayesianEvidenceDao evidenceDao;
    private final BayesianDecisionEngineLite decisionEngine;

    public BayesianActionPolicyLiteService(BayesianEvidenceDao evidenceDao,
                                           BayesianDecisionEngineLite decisionEngine) {
        this.evidenceDao = evidenceDao;
        this.decisionEngine = decisionEngine;
    }

    public ActionSelection select(long tenantId, String taskPatternKey, List<String> eligibleActions) {
        if (blank(taskPatternKey) || eligibleActions == null || eligibleActions.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        ActionSelection best = null;
        for (String action : eligibleActions) {
            String normalized = action == null ? null : action.trim().toUpperCase();
            if (blank(normalized)) {
                throw new BizException(ErrorCode.PARAM_INVALID);
            }
            BayesianEvidenceDO latest = evidenceDao.findLatest(tenantId, ACTION_MODEL_ASSET_TYPE,
                    ACTION_MODEL_ASSET_ID, posteriorType(normalized), taskPatternKey);
            BayesianDecisionEngineLite.BetaPosterior posterior = decisionEngine.posterior(
                    latest == null ? null : latest.getAlpha(),
                    latest == null ? null : latest.getBeta(),
                    latest == null ? null : latest.getPosteriorMean(),
                    latest == null ? null : latest.getEffectiveSampleSize());
            // One-posterior-standard-deviation upper bound is a compact Bayesian exploration rule:
            // successful actions stay preferred; untried actions are explored after failures.
            double explorationScore = Math.min(1.0,
                    posterior.mean() + Math.sqrt(Math.max(0.0, posterior.variance())));
            ActionSelection current = new ActionSelection(normalized, posterior.mean(),
                    posterior.effectiveSampleSize(), explorationScore);
            if (best == null || current.explorationScore() > best.explorationScore()) {
                best = current;
            }
        }
        return best;
    }

    static String posteriorType(String action) {
        return "ACTION_" + action;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public record ActionSelection(String action, double posteriorMean,
                                  double effectiveSampleSize, double explorationScore) {
    }
}
