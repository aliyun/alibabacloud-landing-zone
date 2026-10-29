package com.aliyun.autowonder.agent.dto;

import com.aliyun.autowonder.agent.AgentEnvironmentVariableRefVO;
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
    /** REST compatibility field containing the digital worker's SOUL.md Markdown content. */
    private String businessBackground;
    /** REST compatibility field containing the digital worker's AGENT.md Markdown content. */
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
    private List<AgentEnvironmentVariableRefVO> environmentVariables;

    @Getter
    @Setter
    public static class RepoPermItem {
        private Long repoId;
        /** Repository name for display; null when the repository was removed or is not resolvable. */
        private String repoName;
        private String permLevel;
        private List<String> allowedBranchPatterns;
    }

    @Getter
    @Setter
    public static class SkillItem {
        private Long skillId;
        /** Capability name for display; null when the bound capability was removed or is not resolvable. */
        private String skillName;
    }

    @Getter
    @Setter
    public static class MemoryRefItem {
        private Long memoryId;
        private String source;
    }
}
