package com.aliyun.autowonder.evolution;

import org.springframework.stereotype.Component;

@Component
public class BayesianDecisionEngineLite {

    static final double PRIOR_ALPHA = 1.0;
    static final double PRIOR_BETA = 1.0;

    public BetaPosterior posterior(Double alpha, Double beta, Double mean, Double effectiveSampleSize) {
        if (alpha != null && beta != null && alpha > 0 && beta > 0) {
            return new BetaPosterior(alpha, beta);
        }
        double safeMean = mean == null ? 0.5 : Math.max(0.01, Math.min(0.99, mean));
        double sample = effectiveSampleSize == null ? 0.0 : Math.max(0.0, effectiveSampleSize);
        double total = sample + PRIOR_ALPHA + PRIOR_BETA;
        return new BetaPosterior(safeMean * total, (1.0 - safeMean) * total);
    }

    public double probabilityBelow(BetaPosterior posterior, double threshold) {
        return normalCdf((threshold - posterior.mean()) / posterior.stddev());
    }

    public double probabilityAbove(BetaPosterior posterior, double threshold) {
        return 1.0 - probabilityBelow(posterior, threshold);
    }

    public PosteriorComparison compare(BetaPosterior baseline, BetaPosterior candidate, double minLift) {
        double expectedLift = candidate.mean() - baseline.mean();
        double stddev = Math.sqrt(Math.max(1e-9, baseline.variance() + candidate.variance()));
        double winProbability = 1.0 - normalCdf((minLift - expectedLift) / stddev);
        double loseProbability = normalCdf((-minLift - expectedLift) / stddev);
        return new PosteriorComparison(winProbability, loseProbability, expectedLift);
    }

    private double normalCdf(double value) {
        return 0.5 * (1.0 + erf(value / Math.sqrt(2.0)));
    }

    private double erf(double x) {
        double sign = x < 0 ? -1.0 : 1.0;
        double abs = Math.abs(x);
        double t = 1.0 / (1.0 + 0.3275911 * abs);
        double y = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741)
                * t - 0.284496736) * t + 0.254829592) * t * Math.exp(-abs * abs);
        return sign * y;
    }

    public record BetaPosterior(double alpha, double beta) {
        public double mean() {
            return alpha / (alpha + beta);
        }

        public double effectiveSampleSize() {
            return alpha + beta - PRIOR_ALPHA - PRIOR_BETA;
        }

        public double variance() {
            double sum = alpha + beta;
            return (alpha * beta) / (sum * sum * (sum + 1.0));
        }

        double stddev() {
            return Math.sqrt(Math.max(1e-9, variance()));
        }
    }

    public record PosteriorComparison(double winProbability, double loseProbability, double expectedLift) {
    }
}
