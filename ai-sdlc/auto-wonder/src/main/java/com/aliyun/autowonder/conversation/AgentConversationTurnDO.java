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
    private Long submittedUserId;
    private String status;
    private String error;
    private Long sourceInboundTurnId;
    private String deliveryStatus;
    private Integer deliveryAttempt;
    private Date deliveryAvailableAt;
    private String deliveryError;
    private Date lastDispatchAt;
    private Integer dispatchAttempt;
    private Date gmtCreate;
}
