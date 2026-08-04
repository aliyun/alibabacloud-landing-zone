package com.aliyun.autowonder.repo;

import lombok.Getter;
import lombok.Setter;
import java.util.Date;

@Getter
@Setter
public class RepoRelationDO {
    private Long id;
    private Long tenantId;
    private Long fromRepoId;
    private Long toRepoId;
    private String relationType;
    private String description;
    private Long aiSessionId;
    private Date gmtCreate;
    private Date gmtModified;
    private Long creatorId;
    private Long modifierId;
    private Integer isDeleted;
}
