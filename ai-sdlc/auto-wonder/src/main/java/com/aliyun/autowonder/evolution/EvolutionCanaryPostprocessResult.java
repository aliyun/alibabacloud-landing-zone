package com.aliyun.autowonder.evolution;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class EvolutionCanaryPostprocessResult {
    private Long proposalId;
    private String verdict;
    private String action;
}
