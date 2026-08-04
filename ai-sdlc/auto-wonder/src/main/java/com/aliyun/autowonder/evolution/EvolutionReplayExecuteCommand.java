package com.aliyun.autowonder.evolution;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class EvolutionReplayExecuteCommand {
    private Long proposalId;
    private Boolean autoValidate;
    private String replaySuiteJson;
}
