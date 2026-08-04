package com.aliyun.autowonder.evolution;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class EvolutionGateRunCommand {
    private Long proposalId;
    private String gateType;
    private String verdict;
    private String resultJson;
}
