package com.aliyun.autowonder.evolution;

import org.springframework.stereotype.Component;

@Component
public class BayesianTriggerPolicyLite {

    private static final double Z_90 = 1.2815515655446004;

    public boolean shouldInvestigate(BayesianEvidenceDO latest, double minEffectiveSampleSize,
                                     double credibleUpperBoundBelow) {
        if (latest == null || latest.getAlpha() == null || latest.getBeta() == null
                || latest.getEffectiveSampleSize() == null
                || latest.getEffectiveSampleSize() < minEffectiveSampleSize) {
            return false;
        }
        return credibleUpperBound90(latest.getAlpha(), latest.getBeta()) < credibleUpperBoundBelow;
    }

    double credibleUpperBound90(double alpha, double beta) {
        double sum = alpha + beta;
        if (sum <= 0) {
            return 1.0;
        }
        double mean = alpha / sum;
        double variance = (alpha * beta) / (sum * sum * (sum + 1.0));
        return Math.min(1.0, mean + Z_90 * Math.sqrt(Math.max(variance, 0.0)));
    }
}
