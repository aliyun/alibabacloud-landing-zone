package com.aliyun.autowonder.repo;

import lombok.Getter;
import lombok.Setter;
import java.util.Date;

@Getter
@Setter
public class RepoConclusionDO {
    private Long id;
    private Long tenantId;
    private Long repoId;
    private String purpose;
    private String keyBusiness;
    private String upstreams;
    private String downstreams;
    private String summaryMd;
    private Long aiSessionId;
    private Integer version;
    private Date gmtCreate;
    private Date gmtModified;
    private Long creatorId;
    private Long modifierId;
    private Integer isDeleted;
}
