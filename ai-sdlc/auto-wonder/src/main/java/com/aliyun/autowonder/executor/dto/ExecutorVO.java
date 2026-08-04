package com.aliyun.autowonder.executor.dto;

import lombok.Getter;
import lombok.Setter;
import java.util.Date;

@Getter
@Setter
public class ExecutorVO {
    private Long id;
    private Long agentId;
    private String agentName;
    private String name;
    private String status;       // reflects live online status
    private String clientKind;
    private String lastConnectIp;
    private Date lastHeartbeat;
    private Date gmtCreate;
}
