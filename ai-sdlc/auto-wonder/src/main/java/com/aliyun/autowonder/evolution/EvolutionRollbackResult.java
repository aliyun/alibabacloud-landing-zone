package com.aliyun.autowonder.evolution;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class EvolutionRollbackResult {
    private Long proposalId;
    private Long assetId;
    private String assetType;
    private String status;
    private String action;
}
