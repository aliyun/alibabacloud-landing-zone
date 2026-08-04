package com.aliyun.autowonder.agent;

import lombok.Getter;
import lombok.Setter;
import java.util.Date;

@Getter
@Setter
public class AgentDO {
    private Long id;
    private Long tenantId;
    private String name;
    private String avatarUrl;
    private String status;
    private Long onlineVersionId;
    private Long editingVersionId;
    private Integer latestVersionNo;
    private Date gmtCreate;
    private Date gmtModified;
    private Long creatorId;
    private Long modifierId;
    private Integer isDeleted;
    private Integer version;
}
