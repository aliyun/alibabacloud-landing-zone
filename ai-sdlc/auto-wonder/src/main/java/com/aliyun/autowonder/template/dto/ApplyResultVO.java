package com.aliyun.autowonder.template.dto;

import lombok.Getter;
import lombok.Setter;
import java.util.List;

@Getter
@Setter
public class ApplyResultVO {
    private Long squadId;
    private List<AgentInfo> agents;

    @Getter
    @Setter
    public static class AgentInfo {
        private Long agentId;
        private String roleName;
        private String roleCode;
    }
}
