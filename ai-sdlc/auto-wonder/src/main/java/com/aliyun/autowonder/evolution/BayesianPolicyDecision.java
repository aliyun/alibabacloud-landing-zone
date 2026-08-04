package com.aliyun.autowonder.evolution;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class BayesianPolicyDecision {
    private String action;
    private boolean shouldEvolve;
    private Double confidence;
    private String reasonCode;
    private String reason;
    private String targetContextKey;
    private String dominantFailureMode;
    private String rewriteBrief;
    private Double posteriorMean;
    private Double effectiveSampleSize;
    private String policyJson;
}
