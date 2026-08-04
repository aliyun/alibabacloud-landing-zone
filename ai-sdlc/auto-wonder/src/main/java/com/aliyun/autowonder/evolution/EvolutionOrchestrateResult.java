package com.aliyun.autowonder.evolution;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class EvolutionOrchestrateResult {
    private Long evidenceId;
    private BayesianPolicyDecision policyDecision;
    private Long proposalId;
    private String proposalStatus;
    private String replayVerdict;
    private EvolutionTrialDecision trialDecision;
    private String action;
}
