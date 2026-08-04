package com.aliyun.autowonder.conversation;

import lombok.Data;
import java.util.Date;

@Data
public class AgentConversationDO {
    private Long id;
    private Long tenantId;
    private Long agentId;
    private Long agentVersionId;
    private String channel;
    private String bizRefType;
    private Long bizRefId;
    private String channelConversationId;
    private String cliSessionRef;
    private Long executorId;
    private String status;
    private Date lastTurnAt;
    private Date gmtCreate;
    private Date gmtModified;
    private Integer version;
}
