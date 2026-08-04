package com.aliyun.autowonder.evolution;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class EvolutionTrialDecision {
    private Long proposalId;
    private String decision;
    private String proposalStatus;
    private String reasonCode;
    private String taskPatternKey;
    private Double baselinePosteriorMean;
    private Double baselineEffectiveSampleSize;
    private Double candidatePosteriorMean;
    private Double candidateEffectiveSampleSize;
    private Double posteriorWinProbability;
    private Double posteriorLoseProbability;
    private Double expectedLift;
	private String targetPosteriorType;
	private Double reliabilityGuardProbability;
}
