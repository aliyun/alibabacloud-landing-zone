package com.aliyun.autowonder.evolution;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
public class BayesianEvidenceDO {
    private Long id;
    private Long tenantId;
    private String assetType;
    private Long assetId;
    private String posteriorType;
    private String contextKey;
    private String sourceType;
    private String sourceRef;
    private String outcome;
    private Double weight;
    private String evidenceJson;
    private String dependencyGroup;
    private String idempotencyKey;
    private Double alpha;
    private Double beta;
    private Double posteriorMean;
    private Double effectiveSampleSize;
    private Date gmtCreate;
    private Long creatorId;
}
