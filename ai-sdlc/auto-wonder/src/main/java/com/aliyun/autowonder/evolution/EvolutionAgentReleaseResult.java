package com.aliyun.autowonder.evolution;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class EvolutionAgentReleaseResult {
    private Long proposalId;
    private String action;
    private String status;
}
