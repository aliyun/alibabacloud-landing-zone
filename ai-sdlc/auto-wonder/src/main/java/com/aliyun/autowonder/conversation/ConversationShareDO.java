package com.aliyun.autowonder.conversation;

import lombok.Data;
import java.util.Date;

@Data
public class ConversationShareDO {
    private Long id;
    private Long tenantId;
    private Long conversationId;
    private Long granteeUserId;
    private String permission;
    private Long createdBy;
    private Date revokedAt;
    private Date gmtCreate;
}
