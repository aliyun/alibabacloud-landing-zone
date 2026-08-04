package com.aliyun.autowonder.evolution;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
public class EvolutionGateRunDO {
    private Long id;
    private Long tenantId;
    private Long proposalId;
    private String gateType;
    private String verdict;
    private String resultJson;
    private Date gmtCreate;
    private Long creatorId;
}
