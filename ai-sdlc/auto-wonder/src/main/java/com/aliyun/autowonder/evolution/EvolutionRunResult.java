package com.aliyun.autowonder.evolution;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class EvolutionRunResult {
    private Long proposalId;
    private String status;
    private String assetType;
    private Long assetId;
}
