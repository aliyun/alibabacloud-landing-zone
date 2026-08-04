package com.aliyun.autowonder.conversation;

import lombok.Data;
import java.util.Date;

@Data
public class AgentConversationTurnEventDO {
    private Long id;
    private Long tenantId;
    private Long conversationId;
    private Long turnId;
    private Integer dispatchAttempt;
    private Long eventSeq;
    private Integer chunkIndex;
    private Integer chunkCount;
    private String eventType;
    private String payloadFragment;
    private Date gmtCreate;
}
