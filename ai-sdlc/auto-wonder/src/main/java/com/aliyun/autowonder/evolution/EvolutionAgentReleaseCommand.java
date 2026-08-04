package com.aliyun.autowonder.evolution;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class EvolutionAgentReleaseCommand {
    private Boolean allowRelease;
    private List<String> requiredGateTypes;
    private Boolean allowCanaryInconclusive;
}
