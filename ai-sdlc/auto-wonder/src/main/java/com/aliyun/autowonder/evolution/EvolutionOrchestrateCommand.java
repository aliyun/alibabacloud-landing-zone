package com.aliyun.autowonder.evolution;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class EvolutionOrchestrateCommand {
    private EvidenceLedgerEventCommand evidenceEvent;
    private String candidateAssetType;
    private Long candidateAssetId;
    private String rootEvidenceJson;
    private String failureSummary;
    private String suggestedPatchJson;
    private String draftDeltaJson;
    private String contextKey;
    private Long sourceAgentId;
    private Boolean autoValidateBeforeReplay;
    private String replaySuiteJson;
}
