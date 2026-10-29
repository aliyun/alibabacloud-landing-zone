package com.aliyun.autowonder.conversation;

import lombok.Data;

import java.util.Date;

/**
 * ACP elicitation（Agent 向用户提问）的挂起请求。生命周期为
 * PENDING → ANSWERED / DECLINED / EXPIRED / CANCELED，终态不可再变。
 */
@Data
public class AgentConversationElicitationDO {
    private Long id;
    private Long tenantId;
    private Long conversationId;
    private Long turnId;
    /** 执行器用 crypto/rand 生成的 32 位十六进制，是回答路由的唯一键。 */
    private String requestId;
    private String mode;
    private String message;
    /** ACP requestedSchema 原样存储，前端据此渲染表单。 */
    private String schemaJson;
    private String status;
    private String answerJson;
    private Integer answerProtocolVersion;
    private Integer dispatchAttempt;
    private Long executorId;
    private String answerId;
    private String deliveryStatus;
    /** Transient transport instruction; not persisted or exposed as a user decision. */
    private boolean receiptOnly;
    private Date deliveryDeadline;
    private Date nextDeliveryAt;
    private Date gmtCreate;
    private Date gmtModified;
}
