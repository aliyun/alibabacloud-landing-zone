package com.aliyun.autowonder.evolution;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/** Converts an exact lower-is-better metric into a cohort-relative soft observation. */
@Service
public class EvolutionHistoricalPercentileLiteService {

	private static final int HISTORY_LIMIT = 500;
	private static final double COHORT_PRIOR_STRENGTH = 8.0;

	private final BayesianEvidenceDao evidenceDao;

	public EvolutionHistoricalPercentileLiteService(BayesianEvidenceDao evidenceDao) {
		this.evidenceDao = evidenceDao;
	}

	public double lowerIsBetter(long tenantId, String contextKey, String posteriorType, double rawValue) {
		List<Double> context = values(evidenceDao.listRecentCohortSamples(
				tenantId, posteriorType, contextKey, HISTORY_LIMIT));
		List<Double> tenant = values(evidenceDao.listRecentCohortSamples(
				tenantId, posteriorType, null, HISTORY_LIMIT));
		if (context.isEmpty() && tenant.isEmpty()) {
			return 0.5;
		}
		double tenantPercentile = percentile(tenant, rawValue);
		if (context.isEmpty()) {
			return 1.0 - tenantPercentile;
		}
		double contextWeight = context.size() / (context.size() + COHORT_PRIOR_STRENGTH);
		double blended = contextWeight * percentile(context, rawValue)
				+ (1.0 - contextWeight) * tenantPercentile;
		return clamp(1.0 - blended);
	}

	private List<Double> values(List<BayesianEvidenceDO> evidence) {
		List<Double> values = new ArrayList<>();
		if (evidence == null) {
			return values;
		}
		for (BayesianEvidenceDO row : evidence) {
			if (row == null || row.getEvidenceJson() == null) {
				continue;
			}
			try {
				Object parsed = JSON.parse(row.getEvidenceJson());
				if (parsed instanceof JSONObject object) {
					Double value = object.getDouble("rawMetricValue");
					if (value != null && Double.isFinite(value)) {
						values.add(value);
					}
				}
			} catch (RuntimeException ignored) {
				// Historical malformed rows are skipped; new evidence remains usable.
			}
		}
		return values;
	}

	private double percentile(List<Double> samples, double value) {
		if (samples.isEmpty()) {
			return 0.5;
		}
		double below = 0.0;
		for (double sample : samples) {
			if (sample < value) {
				below += 1.0;
			} else if (Double.compare(sample, value) == 0) {
				below += 0.5;
			}
		}
		return (below + 0.5) / (samples.size() + 1.0);
	}

	private double clamp(double value) {
		return Math.max(0.0, Math.min(1.0, value));
	}
}
