package com.aliyun.autowonder.evolution;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class EvidenceLedgerEventCommand {
    private String assetType;
    private Long assetId;
    private String posteriorType;
    private String contextKey;
    private String sourceType;
    private String sourceRef;
    private String rawOutcome;
	private Double observation;
    private String rawEventJson;
    private Double weight;
    private String dependencyGroup;
    private String idempotencyKey;
}
