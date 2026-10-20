package com.aliyun.autowonder.conversation;

import lombok.Data;

import java.util.Date;

@Data
public class ClarificationAttachmentDO {
    private Long id;
    private Long tenantId;
    private Long workitemId;
    private Long conversationId;
    private Long turnId;
    private Long uploaderUserId;
    private String name;
    private String contentType;
    private Long sizeBytes;
    private String ossRef;
    private Integer previewable;
    private Date deletingAt;
    private Date gmtCreate;
}
