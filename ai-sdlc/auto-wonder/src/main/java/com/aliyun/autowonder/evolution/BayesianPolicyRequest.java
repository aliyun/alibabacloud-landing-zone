package com.aliyun.autowonder.evolution;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class BayesianPolicyRequest {
    private String assetType;
    private Long assetId;
    private String posteriorType;
    private String contextKey;
    private Double minEffectiveSampleSize;
}
