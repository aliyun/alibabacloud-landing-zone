package com.aliyun.autowonder.executor;

import lombok.Getter;
import lombok.Setter;
import java.util.Date;

@Getter
@Setter
public class ExecutorDO {
    private Long id;
    private Long tenantId;
    private Long agentId;
    private String agentName;
    private String name;
    private String tokenRef;
    private String status;        // OFFLINE/ONLINE/BUSY
    private Date lastHeartbeat;
    private String clientKind;
    private String lastConnectIp;
    private Date gmtCreate;
    private Date gmtModified;
    private Long creatorId;
    private Long modifierId;
    private Integer isDeleted;
}
