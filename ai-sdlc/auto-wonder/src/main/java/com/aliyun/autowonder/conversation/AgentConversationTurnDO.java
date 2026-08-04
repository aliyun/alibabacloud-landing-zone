package com.aliyun.autowonder.conversation;

import lombok.Data;
import java.util.Date;

@Data
public class AgentConversationTurnDO {
    private Long id;
    private Long tenantId;
    private Long conversationId;
    private String direction; // IN / OUT
    private String content;
    private String externalMsgId;
    private String requestId;
    private String sourceContext;
    private String status;
    private String error;
    private Date lastDispatchAt;
    private Integer dispatchAttempt;
    private Date gmtCreate;
}
