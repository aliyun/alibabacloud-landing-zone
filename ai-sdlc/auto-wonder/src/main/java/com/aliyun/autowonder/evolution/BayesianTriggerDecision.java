package com.aliyun.autowonder.evolution;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class BayesianTriggerDecision {
    private boolean shouldInvestigate;
    private Double posteriorMean;
    private Double effectiveSampleSize;
    private Double credibleUpperBound90;
}
