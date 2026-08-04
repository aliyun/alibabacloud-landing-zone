package com.aliyun.autowonder.evolution;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class BayesianEvidenceCommand {
    private String assetType;
    private Long assetId;
    private String posteriorType;
    private String contextKey;
    private String sourceType;
    private String sourceRef;
    private String outcome;
	private Double observation;
    private Double weight;
    private String evidenceJson;
    private String dependencyGroup;
    private String idempotencyKey;
}
