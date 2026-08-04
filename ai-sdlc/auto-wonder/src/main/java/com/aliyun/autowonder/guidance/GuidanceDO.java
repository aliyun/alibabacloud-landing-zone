package com.aliyun.autowonder.guidance;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
public class GuidanceDO {
    private Long id;
    private Long tenantId;
    private Long workitemId;
    private Long commentId;
    private Long targetAgentId;
    private Long dispatchId;
    private Long executorId;
    private Long replyCommentId;
    private String status;
    private String error;
    private Date deliveredAt;
    private Date appliedAt;
    private Date gmtCreate;
    private Date gmtModified;
}
