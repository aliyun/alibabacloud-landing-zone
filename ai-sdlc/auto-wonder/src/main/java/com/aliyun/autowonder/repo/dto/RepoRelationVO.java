package com.aliyun.autowonder.repo.dto;

import lombok.Getter;
import lombok.Setter;
import java.util.Date;

@Getter
@Setter
public class RepoRelationVO {
    private Long id;
    private Long fromRepoId;
    private Long toRepoId;
    private String relationType;
    private String description;
    private Long aiSessionId;
    private Date gmtCreate;
}
