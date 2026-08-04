package com.aliyun.autowonder.evolution;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class EvolutionRunCommand {
    private String assetType;
    private Long assetId;
    private String rootEvidenceJson;
    private String policyJson;
    private String failureSummary;
    private String suggestedPatchJson;
    private String contextKey;
    private Long sourceAgentId;
}
