package com.aliyun.autowonder.workitem.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
public class CommentInteractionVO {
    private Long guidanceId;
    private Long dispatchId;
    private String executionStatus;
    private Long targetAgentId;
    private String targetAgentName;
    private String status;
    private String error;
    private Long replyCommentId;
    private String replyContent;
    private Date repliedAt;
    /** True only when this guidance is bound to an interaction dispatch owned by this workitem. */
    private boolean controlEligible;
    private boolean cancelRequested;
    private boolean stopPending;
    /** Only end/forceEnd are exposed for comment interactions. */
    private DispatchControlCapabilitiesVO control;
}
