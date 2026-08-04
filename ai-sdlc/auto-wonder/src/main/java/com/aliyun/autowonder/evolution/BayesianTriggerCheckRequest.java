package com.aliyun.autowonder.evolution;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class BayesianTriggerCheckRequest {
    private String assetType;
    private Long assetId;
    private String posteriorType;
    private String contextKey;
    private Double minEffectiveSampleSize;
    private Double credibleUpperBoundBelow;
}
