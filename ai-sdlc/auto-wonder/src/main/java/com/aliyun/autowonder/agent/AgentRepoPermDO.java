package com.aliyun.autowonder.agent;

import lombok.Getter;
import lombok.Setter;
import java.util.Date;

@Getter
@Setter
public class AgentRepoPermDO {
    private Long id;
    private Long tenantId;
    private Long agentVersionId;
    private Long repoId;
    private String permLevel;
    private Date gmtCreate;
}
