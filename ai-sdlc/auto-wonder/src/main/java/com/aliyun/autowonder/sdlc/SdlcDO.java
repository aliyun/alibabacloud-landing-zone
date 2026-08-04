package com.aliyun.autowonder.sdlc;

import lombok.Getter;
import lombok.Setter;
import java.util.Date;

@Getter
@Setter
public class SdlcDO {
    private Long id;
    private Long tenantId;
    private String name;
    private String description;
    private String workType;
    private String status;
    private Integer isDefault;
    private Long entryStepId;
    private Date gmtCreate;
    private Date gmtModified;
    private Long creatorId;
    private Long modifierId;
    private Integer isDeleted;
    private Integer version;
}
