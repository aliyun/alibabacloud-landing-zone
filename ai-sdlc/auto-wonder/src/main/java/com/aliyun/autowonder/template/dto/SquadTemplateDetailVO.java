package com.aliyun.autowonder.template.dto;

import lombok.Getter;
import lombok.Setter;
import java.util.List;

@Getter
@Setter
public class SquadTemplateDetailVO {
    private Long id;
    private String name;
    private String description;
    private Integer squadSize;
    private String icon;
    private List<String> tags;
    private boolean system;
    private SquadInfo squad;
    private List<AgentDetail> agents;

    @Getter
    @Setter
    public static class SquadInfo {
        private String name;
        private String description;
    }

    @Getter
    @Setter
    public static class AgentDetail {
        private String name;
        private String roleCode;
        private String roleName;
        private String responsibilities;
        private SdlcDetail sdlc;
    }

    @Getter
    @Setter
    public static class SdlcDetail {
        private String name;
        private String description;
        private List<StepSummary> steps;
    }

    @Getter
    @Setter
    public static class StepSummary {
        private Integer order;
        private String name;
        private String kind;
    }
}
