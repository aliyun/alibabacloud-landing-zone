package com.aliyun.autowonder.agent.dto;

import lombok.Getter;
import lombok.Setter;
import java.util.Date;
import java.util.List;

@Getter
@Setter
public class AgentVersionVO {
    private Long id;
    private Long agentId;
    private Integer versionNo;
    private String status;
    private String roleName;
    private String roleCode;
    private String businessBackground;
    private String responsibilities;
    private Long sdlcId;
    private String identityJson;
    private String evolutionMode;
    private Long reviewerId;
    private String reviewComment;
    private Date reviewedAt;
    private Integer version;
    private Date gmtCreate;
    private List<RepoPermItem> repoPerms;
    private List<SkillItem> skills;
    private List<MemoryRefItem> memoryRefs;

    @Getter
    @Setter
    public static class RepoPermItem {
        private Long repoId;
        private String permLevel;
    }

    @Getter
    @Setter
    public static class SkillItem {
        private Long skillId;
    }

    @Getter
    @Setter
    public static class MemoryRefItem {
        private Long memoryId;
        private String source;
    }
}
