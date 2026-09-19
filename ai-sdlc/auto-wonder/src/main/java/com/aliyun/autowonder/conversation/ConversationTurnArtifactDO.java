package com.aliyun.autowonder.conversation;

import lombok.Data;
import java.util.Date;

@Data
public class ConversationTurnArtifactDO {
    private Long id;
    private Long tenantId;
    private Long conversationId;
    private Long turnId;
    private Long artifactId;
    private String direction;
    private String referenceMode;
    private Integer displayOrder;
    private String manifestJson;
    private Date gmtCreate;
}
