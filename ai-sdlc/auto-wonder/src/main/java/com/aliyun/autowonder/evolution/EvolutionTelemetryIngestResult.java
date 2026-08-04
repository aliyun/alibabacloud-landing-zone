package com.aliyun.autowonder.evolution;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class EvolutionTelemetryIngestResult {
    private Long dispatchId;
    private String taskPatternKey;
    private String outcome;
	private boolean eligible = true;
    private String coverageHypothesisOutcome;
    private String failureMode;
    private String tokenBucket;
    private String turnBucket;
    private String repairBucket;
    private String toolFailureBucket;
    private String skillCoverageBucket;
	private long totalTokens;
	private long turns;
	private long repairs;
	private long toolFailures;
	private long repeatToolCalls;
	private long elapsedMs;
    private List<Long> skillIds = new ArrayList<>();
    private List<Long> invokedSkillIds = new ArrayList<>();
    private List<String> skillNeedSignals = new ArrayList<>();
    private List<Long> evidenceIds = new ArrayList<>();
    private BayesianPolicyDecision coverageHypothesisDecision;
    private List<BayesianPolicyDecision> skillDecisions = new ArrayList<>();
}
