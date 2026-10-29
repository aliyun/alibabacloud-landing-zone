package com.aliyun.autowonder.conversation.dto;

import lombok.Builder;
import lombok.Data;

import java.util.Date;

/**
 * 未解决问答卡片的回显。浏览器刷新后前端据此恢复卡片，而不是把用户卡在
 * 一个既没有问题也没有输入框的空白处。
 */
@Data
@Builder
public class ClarificationElicitationVO {
    private String requestId;
    private Long turnId;
    private String mode;
    private String message;
    /** ACP requestedSchema 原样透传，前端按 JSON Schema 渲染表单。 */
    private String requestedSchema;
    private String status;
    private String answerId;
    private String deliveryStatus;
    private String answerJson;
    private Integer dispatchAttempt;
    private Date gmtCreate;

    public static ClarificationElicitationVO fromDelivery(com.aliyun.autowonder.conversation.AgentConversationElicitationDO row) {
        return ClarificationElicitationVO.builder().requestId(row.getRequestId()).turnId(row.getTurnId())
                .mode(row.getMode()).message(row.getMessage()).requestedSchema(row.getSchemaJson())
                .status(row.getStatus()).answerId(row.getAnswerId()).deliveryStatus(row.getDeliveryStatus())
                .answerJson(row.getAnswerJson()).dispatchAttempt(row.getDispatchAttempt()).gmtCreate(row.getGmtCreate()).build();
    }
}
