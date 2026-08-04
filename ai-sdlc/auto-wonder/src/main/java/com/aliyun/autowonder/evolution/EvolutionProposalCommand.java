package com.aliyun.autowonder.evolution;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class EvolutionProposalCommand {
    private String assetType;
    private Long assetId;
    private String triggerType;
    private String rootEvidenceJson;
    private String policyJson;
    private String candidatePatchJson;
}
