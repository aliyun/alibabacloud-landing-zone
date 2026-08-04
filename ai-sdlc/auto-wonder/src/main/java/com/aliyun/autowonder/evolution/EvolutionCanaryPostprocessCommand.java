package com.aliyun.autowonder.evolution;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class EvolutionCanaryPostprocessCommand {
    private Long proposalId;
    private String assetType;
    private Long assetId;
    private String contextKey;
    private String verdict;
    private String resultJson;
    private Boolean rejectProposalOnFail;
}
