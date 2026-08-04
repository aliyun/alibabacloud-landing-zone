package com.aliyun.autowonder.evolution;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class EvolutionTrialEvidenceCommand {
    private String rawOutcome;
    private String sourceType;
    private String sourceRef;
    private String evidenceJson;
    private String idempotencyKey;
    private Double weight;
}
