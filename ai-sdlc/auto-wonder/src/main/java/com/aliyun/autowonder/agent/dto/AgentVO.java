package com.aliyun.autowonder.agent.dto;

import com.aliyun.autowonder.agent.AgentEnvironmentVariableRefVO;
import lombok.Getter;
import lombok.Setter;
import java.util.Date;
import java.util.List;

@Getter
@Setter
public class AgentVO {
    private Long id;
    private String name;
    private String avatarUrl;
    private String status;
    /** STANDARD=普通数字员工 / PLATFORM=平台数字人 */
    private String kind;
    private Long onlineVersionId;
    private Long editingVersionId;
    private Integer latestVersionNo;
    private Integer version;
    private Date gmtCreate;
    private String roleName;
    private String roleCode;
    /** REST compatibility field containing the digital worker's SOUL.md Markdown content. */
    private String businessBackground;
    /** REST compatibility field containing the digital worker's AGENT.md Markdown content. */
    private String responsibilities;
    private Long sdlcId;
    /** Evolution mode of the version this VO is displaying, so callers can confirm a change took effect. */
    private String evolutionMode;
    private boolean hasDraft;
    private Integer draftVersionNo;
    private int executorOnlineCount;
    private int executorTotalCount;
    private int skillCount;
    private int memoryCount;
    private int repoPermCount;
    private List<AgentEnvironmentVariableRefVO> environmentVariables;
    /** Squads this digital worker belongs to; empty when unaffiliated. */
    private List<Long> squadIds;
    private List<String> squadNames;
}
