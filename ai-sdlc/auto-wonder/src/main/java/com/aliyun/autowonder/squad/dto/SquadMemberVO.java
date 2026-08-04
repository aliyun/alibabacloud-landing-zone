package com.aliyun.autowonder.squad.dto;

import lombok.Getter;
import lombok.Setter;
import java.util.List;

@Getter
@Setter
public class SquadMemberVO {
    private Long agentId;
    private String agentName;
    private String roleCode;
    private String roleName;
    private String responsibilities;
    private Long sdlcId;
    private String sdlcName;
    private List<SdlcStepSummaryVO> sdlcSteps;

    @Getter
    @Setter
    public static class SdlcStepSummaryVO {
        private Long id;
        private Integer stepOrder;
        private String name;
        private String handlerType;
        private String handlerRoleRef;
    }
}
