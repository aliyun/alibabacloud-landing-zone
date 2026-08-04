package com.aliyun.autowonder.agent.dto;

import lombok.Getter;
import lombok.Setter;
import java.util.Date;

@Getter
@Setter
public class AgentVO {
    private Long id;
    private String name;
    private String avatarUrl;
    private String status;
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
    private int executorOnlineCount;
    private int executorTotalCount;
    private int skillCount;
    private int memoryCount;
    private int repoPermCount;
}
