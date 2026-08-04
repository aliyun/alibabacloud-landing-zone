package com.aliyun.autowonder.ai;

import lombok.Getter;
import lombok.Setter;
import java.util.Date;

@Getter
@Setter
public class AiSessionDO {
    private Long id;
    private Long tenantId;
    private String scene;
    private String bizRefType;
    private Long bizRefId;
    private String status;
    private String cliSessionRef;
    private String nodeId;
    private String resultJson;
    private String error;
    private Date gmtCreate;
    private Date gmtModified;
    private Long creatorId;
    private Long modifierId;
    private Integer isDeleted;
    private Integer version;
}
