package com.aliyun.autowonder.conversation.dto;

import lombok.Builder;
import lombok.Data;
import java.util.Date;
import java.util.List;

@Data
@Builder
public class ClarificationConversationVO {
    private Long id;
    private Long agentId;
    private String agentName;
    private String channelConversationId;
    private String status;
    private boolean executorOnline;
    private boolean streamingSupported;
    private boolean cancelSupported;
    private String cliSessionRef;
    private String processingStatus;
    private Long processingTurnId;
    private Date lastTurnAt;
    private Date gmtCreate;
    private List<ClarificationTurnVO> turns;
}
