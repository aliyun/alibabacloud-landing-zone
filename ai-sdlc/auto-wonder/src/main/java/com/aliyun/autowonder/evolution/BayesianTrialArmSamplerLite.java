package com.aliyun.autowonder.evolution;

import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/** Thompson sampling for a live baseline/candidate trial. */
@Component
public class BayesianTrialArmSamplerLite {

	private final BayesianEvidenceDao evidenceDao;
	private final BayesianDecisionEngineLite decisionEngine;

	public BayesianTrialArmSamplerLite(BayesianEvidenceDao evidenceDao,
										 BayesianDecisionEngineLite decisionEngine) {
		this.evidenceDao = evidenceDao;
		this.decisionEngine = decisionEngine;
	}

	public String choose(long tenantId, long proposalId, String taskPatternKey) {
		BayesianEvidenceDO baseline = evidenceDao.findLatest(tenantId, "TRIAL_BASELINE", proposalId,
				"RELIABILITY", taskPatternKey);
		BayesianEvidenceDO candidate = evidenceDao.findLatest(tenantId, "TRIAL_CANDIDATE", proposalId,
				"RELIABILITY", taskPatternKey);
		BayesianDecisionEngineLite.BetaPosterior baselinePosterior = posterior(baseline);
		BayesianDecisionEngineLite.BetaPosterior candidatePosterior = posterior(candidate);
		double baselineDraw = beta(baselinePosterior.alpha(), baselinePosterior.beta());
		double candidateDraw = beta(candidatePosterior.alpha(), candidatePosterior.beta());
		return candidateDraw > baselineDraw ? "CANDIDATE" : "BASELINE";
	}

	private BayesianDecisionEngineLite.BetaPosterior posterior(BayesianEvidenceDO evidence) {
		return decisionEngine.posterior(evidence == null ? null : evidence.getAlpha(),
				evidence == null ? null : evidence.getBeta(),
				evidence == null ? null : evidence.getPosteriorMean(),
				evidence == null ? null : evidence.getEffectiveSampleSize());
	}

	private double beta(double alpha, double beta) {
		double left = gamma(alpha);
		double right = gamma(beta);
		return left / (left + right);
	}

	private double gamma(double shape) {
		ThreadLocalRandom random = ThreadLocalRandom.current();
		if (shape < 1.0) {
			return gamma(shape + 1.0) * Math.pow(random.nextDouble(), 1.0 / shape);
		}
		double d = shape - 1.0 / 3.0;
		double c = 1.0 / Math.sqrt(9.0 * d);
		while (true) {
			double x = random.nextGaussian();
			double v = 1.0 + c * x;
			if (v <= 0.0) continue;
			v = v * v * v;
			double u = random.nextDouble();
			if (u < 1.0 - 0.0331 * x * x * x * x
					|| Math.log(u) < 0.5 * x * x + d * (1.0 - v + Math.log(v))) {
				return d * v;
			}
		}
	}
}
